(ns com.repldriven.queenswood.audit.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]))

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
  "The index ranges a page of `bank-id`'s log narrowed to `kinds` and
  `performer-kinds` is merged from, or nil where neither narrows it."
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

(defn list-by-bank
  [txn bank-id kinds performer-kinds opts]
  (let [opts (assoc opts :order :desc)]
    (fdb/transact
     txn
     (fn [txn]
       (if-let [ranges (ranges bank-id kinds performer-kinds)]
         (update (fdb/scan-appended-union txn ranges opts)
                 :entries
                 (partial loaded txn bank-id))
         (update (fdb/scan-appended txn [log bank-id] opts)
                 :entries
                 (partial mapv decoded))))
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
