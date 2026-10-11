(ns com.repldriven.queenswood.access-log.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private log "access")

(def ^:private by-ref "access-ref")

(def ^:private by-tag "access-tag")

(def ^:private id-prefix "axs.")

(defn entry-id
  [versionstamp]
  (fdb/appended-id id-prefix versionstamp))

(defn entry-versionstamp
  [id]
  (fdb/appended-versionstamp id-prefix id))

(defn- refs
  [entry]
  (distinct (keep entry [:request-id :idempotency-key :correlation-id])))

(defn- index-keys
  [{:keys [bank-id tag] :as entry}]
  (cond-> (mapv (fn [ref] [by-ref bank-id ref]) (refs entry))
          tag
          (conj [by-tag bank-id tag])))

(defn append-all
  [config entries]
  (fdb/transact config
                (fn [txn]
                  (doseq [{:keys [bank-id] :as entry} entries]
                    (fdb/append txn
                                (into [[log bank-id]] (index-keys entry))
                                (schema/AccessEntry->pb entry))))
                :access-log/record
                "Failed to record access entries"))

(defn- decoded
  [{:keys [versionstamp value]}]
  (assoc (schema/pb->AccessEntry value) :versionstamp versionstamp))

(defn- loaded
  [txn bank-id entries]
  (mapv (fn [{:keys [versionstamp]}]
          (decoded {:versionstamp versionstamp
                    :value (fdb/load-appended txn [log bank-id] versionstamp)}))
        entries))

(defn- bounds
  [txn bank-id from to]
  (let [reached (fn [instant]
                  (fdb/seek-appended txn
                                     [log bank-id]
                                     (fn [value]
                                       (>= (:requested-at
                                            (schema/pb->AccessEntry value))
                                           instant))))
        lower (when from (reached from))]
    (if (and from (nil? lower))
      :none
      (utility/assoc-some {} :from lower :until (when to (reached to))))))

(defn list-by-bank
  [txn bank-id ref tags from to opts]
  (let [tagged? (fn [entry] (contains? (set tags) (:tag entry)))]
    (fdb/transact
     txn
     (fn [txn]
       (let [range (bounds txn bank-id from to)
             opts (cond-> (assoc opts :order :desc)
                          (map? range)
                          (merge range))]
         (cond (= :none range)
               {:entries []}

               ref
               (update (fdb/scan-appended txn [by-ref bank-id ref] opts)
                       :entries
                       (fn [found]
                         (cond->> (loaded txn bank-id found)
                           (seq tags)
                           (filterv tagged?))))

               (seq tags)
               (update (fdb/scan-appended-union
                        txn
                        (mapv (fn [tag] [by-tag bank-id tag]) (distinct tags))
                        opts)
                       :entries
                       (partial loaded txn bank-id))

               :else
               (update (fdb/scan-appended txn [log bank-id] opts)
                       :entries
                       (partial mapv decoded)))))
     :access-log/list
     "Failed to list access entries")))

(defn delete-before
  [txn bank-id before limit]
  (fdb/transact
   txn
   (fn [txn]
     (let [found (fdb/scan-appended txn [log bank-id] {:limit limit})]
       (if (error/anomaly? found)
         found
         (let [expired (into []
                             (comp (map decoded)
                                   (take-while (fn [entry]
                                                 (< (:requested-at entry)
                                                    before))))
                             (:entries found))]
           (when (seq expired)
             (doseq [entry expired
                     parts (index-keys entry)]
               (fdb/clear-appended txn parts (:versionstamp entry)))
             (fdb/clear-appended-through txn
                                         [log bank-id]
                                         (:versionstamp (peek expired))))
           (count expired)))))
   :access-log/delete
   "Failed to delete expired access entries"))
