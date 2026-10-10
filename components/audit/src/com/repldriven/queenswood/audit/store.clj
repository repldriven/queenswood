(ns com.repldriven.queenswood.audit.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error])
  (:import
    (java.util Arrays)))

(def ^:private log "audit")

(def ^:private by-subject "audit-subject")

(def ^:private by-kind "audit-kind")

(defn append
  [txn event]
  (let [{:keys [bank-id subject-id kind]} event]
    (fdb/transact txn
                  (fn [txn]
                    (fdb/append txn
                                [[log bank-id]
                                 [by-subject bank-id subject-id]
                                 [by-kind bank-id
                                  (schema/audit-event-kind->int kind)]]
                                (schema/AuditEvent->pb event)))
                  :audit/record
                  "Failed to record an audit event")))

(defn- decoded
  [{:keys [versionstamp value]}]
  (assoc (schema/pb->AuditEvent value) :versionstamp versionstamp))

(defn- loaded
  [txn bank-id entries]
  (mapv (fn [{:keys [versionstamp]}]
          (decoded {:versionstamp versionstamp
                    :value (fdb/load-appended txn [log bank-id] versionstamp)}))
        entries))

(defn- newer
  [a b]
  (Arrays/compareUnsigned ^bytes (:versionstamp b) ^bytes (:versionstamp a)))

(defn- merged
  "One page across several index ranges, each read with the page's
  limit: every range shares the bank's versionstamps, so the newest
  `limit` of their union past the cursor is the page, and a range with
  rows beyond its own page has rows beyond the merged one."
  [pages {:keys [after before limit]}]
  (let [union (sort newer (mapcat :entries pages))
        more? (or (> (count union) limit)
                  (some (if before :before :after) pages))
        page (vec (if before
                    (take-last limit union)
                    (take limit union)))
        rows-before? (if before more? (some? after))
        rows-after? (if before true more?)]
    {:entries page
     :before (when (and rows-before? (seq page))
               (:versionstamp (first page)))
     :after (when (and rows-after? (seq page))
              (:versionstamp (peek page)))}))

(defn list-by-bank
  [txn bank-id kinds opts]
  (let [opts (assoc opts :order :desc)]
    (fdb/transact
     txn
     (fn [txn]
       (if (seq kinds)
         (let [pages (mapv (fn [kind]
                             (fdb/scan-appended
                              txn
                              [by-kind bank-id
                               (schema/audit-event-kind->int kind)]
                              opts))
                           kinds)]
           (if-let [failed (first (filter error/anomaly? pages))]
             failed
             (update (merged pages opts)
                     :entries
                     (partial loaded txn bank-id))))
         (update (fdb/scan-appended txn [log bank-id] opts)
                 :entries
                 (partial mapv decoded))))
     :audit/list
     "Failed to list audit events")))

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
                "Failed to list audit events"))
