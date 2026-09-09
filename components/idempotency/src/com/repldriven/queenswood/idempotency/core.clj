(ns com.repldriven.queenswood.idempotency.core
  (:require
    [com.repldriven.queenswood.idempotency.store :as store]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.edn :as edn]
    [clojure.walk :as walk]))

(def ^:private completed-ttl-ms (* 24 60 60 1000))    ; 24 hours
(def ^:private pending-ttl-ms (* 60 1000))          ; 60 seconds

(defn cacheable-status?
  "Cache definite outcomes (2xx, 4xx) — skip 5xx, which are
  transient and should be retried."
  [status]
  (and (integer? status) (< status 500)))

(defn- expired?
  [entry now]
  (>= now (:expires-at entry)))

(defn- stale-pending?
  "A `pending` entry older than `pending-ttl-ms` is treated as
  abandoned (e.g. handler crashed without releasing) and is
  reclaimable by a fresh request."
  [entry now]
  (>= now (+ (:created-at entry) pending-ttl-ms)))

(defn- live?
  "A `completed` entry that has not expired, or a `pending` one that
  has not gone stale. Anything else is reclaimable."
  [entry now]
  (boolean
   (and entry
        (or (and (= "completed" (:state entry)) (not (expired? entry now)))
            (and (= "pending" (:state entry))
                 (not (stale-pending? entry now)))))))

(defn claim-or-replay
  "Single FDB transaction that atomically inspects the cache and
  decides what to do with a request:

  - `{:type ::completed :body <map> :status <int>}` — cached
    response should be replayed.
  - `{:type ::in-flight}` — another request with the same
    principal+operation+key is currently being processed; caller
    should return 409.
  - `{:type ::mismatch}` — a live entry holds a different request
    under this key; the caller reused it, and no answer of ours
    could be the right one.
  - `{:type ::claimed}` — no live entry; we wrote a `pending`
    placeholder and the caller should run the handler.

  An anomaly from the lookup is returned as itself. `store/lookup`
  runs inside the open transaction, and `fdb/transact` on an open
  `Txn` returns an anomaly *value* rather than throwing, so without
  this the `cond` would bind it as a truthy `existing` with a nil
  `:state`, fall through to `:else`, and run the handler on a cache
  it could not read.

  FDB's optimistic concurrency control serialises concurrent
  callers: if two threads both pass the lookup and try to write
  pending, one transaction will fail and FDB retries it; the retry
  sees the now-pending entry and returns `::in-flight`."
  [config principal-id operation idempotency-key fingerprint]
  (store/transact
   config
   (fn [txn]
     (let [existing (store/lookup txn principal-id operation idempotency-key)
           now (utility/now)]
       (if (error/anomaly? existing)
         existing
         (let [live (live? existing now)
               ;; Absent on an entry written before the field existed,
               ;; which matches anything for the rest of its life.
               stored (not-empty (:fingerprint existing))]
           (cond
            (and live stored (not= stored fingerprint))
            {:type ::mismatch}

            (and live (= "completed" (:state existing)))
            {:type ::completed
             :status (:status existing)
             :body (edn/read-string (:body existing))}

            (and live (= "pending" (:state existing)))
            {:type ::in-flight}

            :else
            (do (store/save txn
                            {:principal-id principal-id
                             :operation operation
                             :idempotency-key idempotency-key
                             :state "pending"
                             :fingerprint fingerprint
                             :created-at now
                             :expires-at (+ now completed-ttl-ms)})
                {:type ::claimed}))))))
   :idempotency/claim-or-replay
   "Failed to claim or replay idempotency entry"))

(defn- plain
  "Every record in `body` as a plain map. A handler's response carries
  the protojure defrecords the schema brick generates, and `pr-str`
  writes one with a tag `edn/read-string` has no reader for — so a
  body holding one could be written and never read back, and the
  replay it was written for would answer 503 instead. The keys and
  values are the record's own, which is all the replay needs."
  [body]
  (walk/postwalk (fn [x] (if (record? x) (into {} x) x)) body))

(defn complete
  "Replace the `pending` marker with a `completed` entry holding the
  response to replay. Called from the interceptor's `:leave` when the
  handler returned a cacheable status (2xx/4xx)."
  [config principal-id operation idempotency-key fingerprint
   {:keys [status body]}]
  (let [now (utility/now)]
    (store/save config
                {:principal-id principal-id
                 :operation operation
                 :idempotency-key idempotency-key
                 :state "completed"
                 :status status
                 :body (pr-str (plain body))
                 :fingerprint fingerprint
                 :created-at now
                 :expires-at (+ now completed-ttl-ms)})))

(defn release
  "Drop the `pending` claim — used when the response wasn't cacheable
  (5xx). Frees the slot so the next request can retry immediately
  rather than waiting out the stale-pending timeout."
  [config principal-id operation idempotency-key]
  (store/delete config principal-id operation idempotency-key))
