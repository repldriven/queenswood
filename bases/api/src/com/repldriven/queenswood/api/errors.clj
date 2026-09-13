(ns com.repldriven.queenswood.api.errors
  (:require
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.log.interface :as log]

    [clojure.string :as str]))

(defn error-response
  "Builds an RFC 9457-shaped ErrorResponse body.

  Two-arity form derives fields from an anomaly.
  Three-arity form derives fields from a command response.
  Four-arity form takes explicit title, type, and detail."
  ([status anomaly]
   (cond-> {:title (cond (error/unauthorized? anomaly)
                         "UNAUTHORIZED"
                         (error/rejection? anomaly)
                         "REJECTED"
                         :else
                         "FAILED")
            :type (str (error/kind anomaly))
            :status status}
           (:message (error/payload anomaly))
           (assoc :detail
                  (:message (error/payload anomaly)))))
  ([status command-status result]
   (cond-> {:title command-status :type (:reason result) :status status}
           (:message result)
           (assoc :detail (:message result))))
  ([status title type detail]
   (cond-> {:title title :type type :status status}
           detail
           (assoc :detail detail))))

(def ^:private rejection-status-overrides
  "Explicit status overrides for rejection categories whose names
  don't fit the default heuristics."
  {:payment/already-submitted 409
   :payment/debtor-account-not-operable 409
   :payment/creditor-account-not-operable 409
   :bank/invalid-status 409
   :cash-account/invalid-status 409
   :cash-account/non-zero-on-close 409
   :cash-account-product/draft-already-exists 409
   :cash-account-product/version-immutable 409
   :cash-account-migration/invalid-status 409
   :interest/no-settlement 404
   :invitation/already-member 409
   :invitation/invalid-status 409
   :gl/missing-currency-account 409
   :ledger-account/invalid-status 409
   :ledger-account/closed 409
   :membership/invalid-status 409
   :membership/last-owner 409
   :party/invalid-status 409
   :party/open-accounts 409
   :policy/limit-exceeded 429
   :webhook-endpoint/invalid-status 409})

(def ^:private error-status-overrides
  "Statuses for error anomalies the storage layer and the external
  providers can name. Not rejections — the request was well-formed, so
  these must not reach the rejection heuristics.

  All are 503 because all mean the same thing to a caller: back off and
  retry. They differ in what they say about the cluster or the upstream,
  not in what to do about it, and retries under load turn one into the
  other — so the distinction rides `type` rather than being overstated
  as several status codes.

  A rate-limited upstream is 503 and not 429 for the same reason, plus
  one of its own: 429 already means `:policy/limit-exceeded` here — the
  caller exceeded a limit of ours. Being throttled by a registry is the
  opposite situation, and a client that read 429 would slow itself down
  for a limit it never hit."
  {:fdb/contention 503
   :fdb/timeout 503
   :company/unavailable 503
   :company/rate-limited 503
   :idv/unavailable 503
   :idv/rate-limited 503
   :payee-check/unavailable 503
   :payment/unavailable 503})

(defn rejection-kind->status
  "Pick an HTTP status code for a rejection kind keyword.

  - explicit override wins, else
  - name ends with `not-found`             -> 404
  - name is `already-exists` / `exists`
    or contains `duplicate`                -> 409
  - anything else rejection                -> 422"
  [kind]
  (let [n (some-> kind
                  name)]
    (cond (contains? rejection-status-overrides kind)
          (get rejection-status-overrides kind)
          (str/ends-with? (or n "") "not-found")
          404
          (or (= n "already-exists")
              (= n "exists")
              (str/includes? (or n "") "duplicate"))
          409
          :else
          422)))

(defn anomaly->status
  "Pick an HTTP status code for an anomaly. Rejection anomalies
  flow through `rejection-kind->status`. Unauthorized -> 403 (the
  caller is authenticated by the time bricks see the request;
  401 is reserved for auth.clj's missing/invalid-credential
  shortfalls). A kind in `error-status-overrides` takes that status.
  Error anomalies -> 500."
  [anomaly]
  (cond (error/unauthorized? anomaly)
        403
        (contains? error-status-overrides (error/kind anomaly))
        (get error-status-overrides (error/kind anomaly))
        (not (error/rejection? anomaly))
        500
        :else
        (rejection-kind->status (error/kind anomaly))))

(defn- log-server-anomaly
  "Log the underlying exception carried in an error anomaly so we can
  diagnose what blew up without surfacing the stack trace to clients."
  [anomaly]
  (let [{:keys [message exception operation]} (error/payload anomaly)
        label (str (error/kind anomaly)
                   (when operation (str " (" operation ")"))
                   ": "
                   (or message "failed"))]
    (if exception
      (log/error exception label)
      (log/error label))))

(defn anomaly->response
  "Map an anomaly to an RFC 9457 ring response via `anomaly->status`.
  Logs the full exception for any anomaly that maps to 5xx so the cause
  is diagnosable even when only a terse message reaches the client."
  [anomaly]
  (let [status (anomaly->status anomaly)]
    (when (<= 500 status) (log-server-anomaly anomaly))
    {:status status :body (error-response status anomaly)}))
