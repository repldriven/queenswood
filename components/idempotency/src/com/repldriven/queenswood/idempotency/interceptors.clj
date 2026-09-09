(ns com.repldriven.queenswood.idempotency.interceptors
  (:require
    [com.repldriven.queenswood.idempotency.core :as core]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.log.interface :as log]

    [sieppari.context :as sc]

    [clojure.string :as str]
    [clojure.walk :as walk])
  (:import
    (java.security MessageDigest)))

(defn- principal-id
  "The principal the cache is scoped by, taken from the auth context:
  the bank id for a service token, `queenswood-admin` for the admin
  client, and the user id for a user. Whatever `authenticate` put on
  the request as `:principal-id`."
  [auth]
  (:principal-id auth))

(defn- canonical
  "Sort every map in `x` by key, so two bodies differing only in the
  order their keys were written hash the same."
  [x]
  (walk/postwalk (fn [form]
                   (if (map? form) (into (sorted-map) form) form))
                 x))

(defn- fingerprint
  "A hash of the request this key is being claimed for: the `:uri`,
  which distinguishes two resources under one path template, and the
  decoded body, which distinguishes two requests to one resource. A
  route with no body hashes the path alone."
  [request]
  (let [material (pr-str [(:uri request) (canonical (:body-params request))])
        digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String material "UTF-8"))]
    ;; A Java byte is signed, so mask before formatting or every byte
    ;; over 127 renders as eight f-padded characters.
    (apply str (map (fn [b] (format "%02x" (bit-and b 0xff))) digest))))

(defn- operation
  "Stable identifier for the route — METHOD + path-template, e.g.
  `POST /v1/cash-accounts`. Scopes the cache so the same key can be
  reused (independently) across different endpoints."
  [request]
  (let [method (some-> (:request-method request)
                       name
                       str/upper-case)
        path (get-in request [:reitit.core/match :template])]
    (when (and method path)
      (str method " " path))))

(defn- in-flight-response
  []
  {:status 409
   :body
   {:title "REJECTED"
    :type "mono/idempotent-request-in-flight"
    :status 409
    :detail
    "A request with this Idempotency-Key is already being processed; please retry in a moment."}})

;; Built here rather than through the API's `errors/anomaly->response`:
;; that lives in the `api` base, which this brick cannot reach, and
;; `mono` has no problem-detail helper of its own.
(defn- key-reused-response
  []
  {:status 422
   :body
   {:title "REJECTED"
    :type "mono/idempotency-key-reused"
    :status 422
    :detail
    "This Idempotency-Key was used for a different request; use a fresh key."}})

(defn- cache-unavailable-response
  []
  {:status 503
   :body
   {:title "FAILED"
    :type "mono/idempotency-cache-unavailable"
    :status 503
    :detail
    "The idempotency cache could not be read; please retry in a moment."}})

(defn- release-or-log
  "Drop the claim, and log rather than raise when that fails: the
  caller is past the point where anything can be done about it, and a
  claim left behind ages out on the stale-pending timeout."
  [config principal-id operation key]
  (let [released (core/release config principal-id operation key)]
    (when (error/anomaly? released)
      (log/error "idempotency claim could not be released"
                 {:principal-id principal-id
                  :operation operation
                  :idempotency-key key
                  :anomaly (error/kind released)}))
    released))

(def cache-response
  "Idempotency cache with concurrent-request protection.

  `:enter` runs an atomic FDB check-and-set. Four outcomes and a
  failure:

  - completed → terminate with the cached `{:status :body}` replay,
                marked `Idempotent-Replayed: true`
  - pending   → terminate with 409 (another request in flight)
  - mismatch  → terminate with 422; the key is live against a
                different request
  - claimed   → wrote a `pending` marker; let the handler run
  - anomaly   → terminate with 503; the cache could not be read, and
                running the handler is what this exists to prevent

  `:leave` finalises the claim:

  - cacheable status (2xx/4xx) → write `completed` entry; a failed
                                  write is logged and released, and
                                  the handler's own response stands
  - 5xx                        → drop the `pending` marker so the
                                  request can be retried immediately

  `:error` releases the claim if the handler threw — Sieppari skips
  `:leave` on the throwing path. Without this, a transient handler
  exception would leave the claim sitting in FDB until the 60s
  stale-pending TTL expires, spuriously 409-ing legitimate retries.

  Place at the route level AFTER `server/require-idempotency-key`
  so the header is known valid; auth has already run by the time
  any route-level interceptor fires."
  {:name ::cache-response
   :enter (fn [ctx]
            (let [request (:request ctx)
                  {:keys [headers auth record-db record-store]} request
                  key (get headers "idempotency-key")
                  pid (principal-id auth)
                  op (operation request)]
              (if-not (and pid op key)
                ctx
                (let [config {:record-db record-db :record-store record-store}
                      fp (fingerprint request)
                      result (core/claim-or-replay config pid op key fp)]
                  (cond
                   ;; The cache could not be read, or held a body that
                   ;; would not parse. Running the handler now would be
                   ;; the one thing this interceptor exists to prevent.
                   (error/anomaly? result)
                   (do (log/error "idempotency cache unavailable"
                                  {:principal-id pid
                                   :operation op
                                   :idempotency-key key
                                   :anomaly (error/kind result)
                                   :cause (error/format-anomaly result)})
                       (sc/terminate ctx (cache-unavailable-response)))

                   :else
                   (case (:type result)
                     ::core/completed
                     (sc/terminate ctx
                                   {:status (:status result)
                                    :body (:body result)
                                    :headers {"Idempotent-Replayed" "true"}})

                     ::core/in-flight (sc/terminate ctx (in-flight-response))

                     ::core/mismatch (sc/terminate ctx (key-reused-response))

                     ::core/claimed
                     (-> ctx
                         (assoc-in [:request :idempotency/principal-id] pid)
                         (assoc-in [:request :idempotency/operation] op)
                         (assoc-in [:request :idempotency/key] key)
                         (assoc-in [:request :idempotency/fingerprint]
                                   fp))))))))
   ;; None of the terminating paths above set the `:idempotency/*`
   ;; request keys, so `:leave` is a no-op on every one of them.
   :leave (fn [ctx]
            (let [{:keys [record-db record-store]
                   :idempotency/keys [principal-id operation key fingerprint]}
                  (:request ctx)
                  response (:response ctx)
                  config {:record-db record-db :record-store record-store}]
              (when (and principal-id operation key response)
                (if (core/cacheable-status? (:status response))
                  ;; The handler's effect has committed, so a failed
                  ;; write must not become the client's answer: that
                  ;; would have it retry something that succeeded.
                  ;; Logged, released, handler's response returned.
                  (let [written (core/complete config
                                               principal-id
                                               operation
                                               key
                                               fingerprint
                                               response)]
                    (when (error/anomaly? written)
                      (log/error "idempotency completion failed"
                                 {:principal-id principal-id
                                  :operation operation
                                  :idempotency-key key
                                  :anomaly (error/kind written)})
                      (release-or-log config principal-id operation key)))
                  (release-or-log config principal-id operation key)))
              ctx))
   :error (fn [ctx]
            (let [{:keys [record-db record-store]
                   :idempotency/keys [principal-id operation key]}
                  (:request ctx)]
              (when (and principal-id operation key)
                (release-or-log {:record-db record-db
                                 :record-store record-store}
                                principal-id
                                operation
                                key))
              ctx))})
