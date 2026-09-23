(ns com.repldriven.queenswood.fdb.transact
  (:require
    [com.repldriven.mono.error.interface :as error :refer [try-nom]]
    [com.repldriven.mono.telemetry.interface :as telemetry])
  (:import
    (com.apple.foundationdb.record LoggableTimeoutException
                                   RecordCoreRetriableTransactionException)
    (com.apple.foundationdb.record.provider.foundationdb
     FDBDatabase
     FDBExceptions$FDBStoreTransactionTimeoutException)
    (java.util.function Function)))

;; Most specific first. `RecordCoreRetriableTransactionException` is the
;; Record Layer's own grouping of conflict, too-old and lock-taken, so
;; matching the parent keeps this from enumerating its subclasses.
(def ^:private exception->kind
  [[LoggableTimeoutException :fdb/timeout]
   [FDBExceptions$FDBStoreTransactionTimeoutException :fdb/timeout]
   [RecordCoreRetriableTransactionException :fdb/contention]])

;; Every level, not just the root: the Record Layer's types wrap the FDB
;; error they describe, so a root-cause walk steps past the very type
;; being matched. Outermost wins as the most proximate description.
(defn- classify
  [^Throwable e]
  (some (fn [^Throwable t]
          (some (fn [[klass kind]]
                  (when (instance? klass t) kind))
                exception->kind))
        (take-while some? (iterate #(.getCause ^Throwable %) e))))

(defn- reclassify
  [result]
  (if-not (error/anomaly? result)
    result
    (let [payload (error/payload result)
          operation (error/kind result)]
      (if-let [kind (classify (:exception payload))]
        (error/fail kind
                    (cond-> payload
                            (not= operation kind)
                            (assoc :operation operation)))
        result))))

(defn- open-store
  [open-store-fn ctx store-name]
  (open-store-fn ctx store-name))

(defrecord Txn [open prefix])

(defn open
  [^Txn txn store-name]
  ((:open txn) store-name))

(defn transact
  ([txn-or-config f]
   (transact txn-or-config f :fdb/transact "Failed to execute transaction"))

  ([txn-or-config f category message]
   (if (instance? Txn txn-or-config)
     (reclassify (try-nom category message (f txn-or-config)))
     (let [{:keys [record-db record-store]} txn-or-config
           keyspace-prefix (or (:keyspace-prefix txn-or-config)
                               (:keyspace-prefix (meta record-store)))]
       (try
         (telemetry/with-span
          {:name "fdb-transaction"
           :attributes {:fdb.category (str category)}}
          (.run ^FDBDatabase record-db
                ^Function
                (fn [ctx]
                  (let [cache (atom {})
                        open-fn (fn [store-name]
                                  (or (get @cache store-name)
                                      (let [s (open-store record-store
                                                          ctx
                                                          store-name)]
                                        (swap! cache assoc store-name s)
                                        s)))
                        result (try-nom category
                                        message
                                        (f (->Txn open-fn keyspace-prefix)))]
                    (if (error/anomaly? result)
                      ;; nosemgrep: no-raw-throw
                      (throw (ex-info "Transaction rolled back"
                                      {::anomaly result}))
                      result)))))
         (catch Exception e
           (reclassify
            (or (::anomaly (ex-data e))
                (error/fail category
                            {:message message
                             :exception e
                             :stack-trace
                             (with-out-str
                               (.printStackTrace
                                e
                                (java.io.PrintWriter. *out*
                                                      true)))})))))))))
