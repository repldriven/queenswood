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

(def ^:private by-request "audit-request")

(def ^:private by-performer-id "audit-performer-id")

(defn entry-id
  [versionstamp]
  (fdb/appended-id id-prefix versionstamp))

(defn entry-versionstamp
  [id]
  (fdb/appended-versionstamp id-prefix id))

(defn append
  [txn entry]
  (let [{:keys [bank-id subject-id kind performed-by request-id]} entry
        kind (schema/audit-kind->int kind)
        performer (schema/actor-kind->int (:kind performed-by))]
    (fdb/transact txn
                  (fn [txn]
                    (fdb/append txn
                                (cond-> [[log bank-id]
                                         [by-subject bank-id subject-id]
                                         [by-kind bank-id kind]
                                         [by-performer bank-id performer]
                                         [by-performer-kind bank-id performer
                                          kind]
                                         [by-performer-id bank-id
                                          (:principal-id performed-by)]]
                                        request-id
                                        (conj [by-request bank-id request-id]))
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
  [txn bank-id filters opts]
  (let [{:keys [kinds performer-kinds request-id performed-by from to]} filters
        kept? (fn [{:keys [kind] :as entry}]
                (and (or (empty? kinds) (contains? (set kinds) kind))
                     (or (empty? performer-kinds)
                         (contains? (set performer-kinds)
                                    (get-in entry [:performed-by :kind])))))]
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

               request-id
               (update
                (fdb/scan-appended txn [by-request bank-id request-id] opts)
                :entries
                (fn [found] (filterv kept? (loaded txn bank-id found))))

               (seq performed-by)
               (update (fdb/scan-appended-union
                        txn
                        (mapv (fn [principal-id]
                                [by-performer-id bank-id principal-id])
                              performed-by)
                        opts)
                       :entries
                       (fn [found] (filterv kept? (loaded txn bank-id found))))

               narrowed
               (update (fdb/scan-appended-union txn narrowed opts)
                       :entries
                       (partial loaded txn bank-id))

               :else
               (update (fdb/scan-appended txn [log bank-id] opts)
                       :entries
                       (partial mapv decoded)))))
     :audit/list
     "Failed to list audit entries")))

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
