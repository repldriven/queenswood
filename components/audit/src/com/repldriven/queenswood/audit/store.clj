(ns com.repldriven.queenswood.audit.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private log "audit")

(def ^:private by-subject "audit-subject")

(def ^:private by-kind "audit-kind")

(def ^:private id-prefix "aud.")

(def ^:private by-performer "audit-performer")

(def ^:private by-performer-kind "audit-performer-kind")

(defn entry-id
  [versionstamp]
  (fdb/appended-id id-prefix versionstamp))

(defn entry-versionstamp
  [id]
  (fdb/appended-versionstamp id-prefix id))

(defn append
  [txn entry]
  (let [{:keys [bank-id subject-id kind performed-by]} entry
        kind (schema/audit-kind->int kind)
        performer (schema/actor-kind->int (:kind performed-by))]
    (fdb/transact txn
                  (fn [txn]
                    (fdb/append txn
                                [[log bank-id]
                                 [by-subject bank-id subject-id]
                                 [by-kind bank-id kind]
                                 [by-performer bank-id performer]
                                 [by-performer-kind bank-id performer kind]]
                                (schema/AuditEntry->pb entry)))
                  :audit/record
                  "Failed to record an audit entry")))

(defn- decoded
  [{:keys [versionstamp value]}]
  (assoc (schema/pb->AuditEntry value) :versionstamp versionstamp))

(defn- loaded
  [txn bank-id entries]
  (mapv (fn [{:keys [versionstamp]}]
          (decoded {:versionstamp versionstamp
                    :value (fdb/load-appended txn [log bank-id] versionstamp)}))
        entries))

(defn- ranges
  [bank-id kinds performer-kinds]
  (let [kinds (map schema/audit-kind->int kinds)
        performers (map schema/actor-kind->int performer-kinds)]
    (cond (and (seq kinds) (seq performers))
          (for [performer performers
                kind kinds]
            [by-performer-kind bank-id performer kind])

          (seq kinds)
          (for [kind kinds]
            [by-kind bank-id kind])

          (seq performers)
          (for [performer performers]
            [by-performer bank-id performer]))))

(defn- bounds
  [txn bank-id from to]
  (let [reached (fn [instant]
                  (fdb/seek-appended txn
                                     [log bank-id]
                                     (fn [value]
                                       (>= (:performed-at
                                            (schema/pb->AuditEntry value))
                                           instant))))
        lower (when from (reached from))]
    (if (and from (nil? lower))
      :none
      (utility/assoc-some {} :from lower :until (when to (reached to))))))

(defn list-by-bank
  [txn bank-id kinds performer-kinds from to opts]
  (fdb/transact
   txn
   (fn [txn]
     (let [range (bounds txn bank-id from to)
           narrowed (ranges bank-id kinds performer-kinds)
           opts (cond-> (assoc opts :order :desc)
                        (map? range)
                        (merge range))]
       (cond (= :none range)
             {:entries []}

             narrowed
             (update (fdb/scan-appended-union txn narrowed opts)
                     :entries
                     (partial loaded txn bank-id))

             :else
             (update (fdb/scan-appended txn [log bank-id] opts)
                     :entries
                     (partial mapv decoded)))))
   :audit/list
   "Failed to list audit entries"))

(defn list-by-subject
  [txn bank-id subject-id opts]
  (fdb/transact txn
                (fn [txn]
                  (update (fdb/scan-appended txn
                                             [by-subject bank-id subject-id]
                                             (assoc opts :order :desc))
                          :entries
                          (partial loaded txn bank-id)))
                :audit/list
                "Failed to list audit entries"))
