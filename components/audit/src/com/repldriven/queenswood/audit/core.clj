(ns com.repldriven.queenswood.audit.core
  (:require
    [com.repldriven.queenswood.audit.store :as store]

    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private left-out
  #{:idempotency-key :created-at :created-by :updated-at :updated-by
    :attempt-count :next-attempt-at :traceparent})

(def ^:private left-out-categories ["user" "system"])

(def ^:private subject-records
  {:audit-subject-kind-bank :bank
   :audit-subject-kind-invitation :invitation
   :audit-subject-kind-member :member
   :audit-subject-kind-party :party
   :audit-subject-kind-verification-session :idv-session
   :audit-subject-kind-cash-account :cash-account
   :audit-subject-kind-product-version :cash-account-product
   :audit-subject-kind-migration :cash-account-migration
   :audit-subject-kind-internal-payment :internal-payment
   :audit-subject-kind-outbound-payment :outbound-payment
   :audit-subject-kind-payee-check :payee-check
   :audit-subject-kind-transaction :transaction
   :audit-subject-kind-webhook-endpoint :webhook-endpoint
   :audit-subject-kind-job :scheduler-job
   :audit-subject-kind-migration-run :cash-account-migration-run
   :audit-subject-kind-job-run :scheduler-run
   :audit-subject-kind-webhook-delivery :webhook-delivery})

(defn- present
  [v]
  (when-not (or (nil? v) (= "" v)) v))

(defn- changeset
  [subject-kind before after]
  (let [record-type (subject-records subject-kind)
        before (schema/without-data-categories record-type
                                               before
                                               left-out-categories)
        after (schema/without-data-categories record-type
                                              after
                                              left-out-categories)
        changed (into []
                      (comp (remove left-out)
                            (distinct)
                            (filter (fn [k]
                                      (not= (present (get before k))
                                            (present (get after k))))))
                      (concat (keys before) (keys after)))
        side (fn [record]
               (not-empty (into {}
                                (keep (fn [k]
                                        (when-some [v (present (get record
                                                                    k))]
                                          [k v])))
                                changed)))]
    (let-nom> [before-json (some-> (side before)
                                   json/write-str)
               after-json (some-> (side after)
                                  json/write-str)]
      (utility/assoc-some {} :before before-json :after after-json))))

(defn record
  [txn entry]
  (let [{:keys [subject-kind before after performed-by]} entry]
    (when performed-by
      (let-nom> [changes (changeset subject-kind before after)]
        (store/append txn
                      (->
                        entry
                        (dissoc :before :after)
                        (utility/assoc-some :changeset (not-empty changes))
                        (update :performed-by select-keys [:kind :principal-id])
                        (update :performed-at (fnil identity (utility/now)))
                        (->> (into {} (filter (comp some? val))))))))))

(defn- read-side
  [json]
  (when json (json/read-str json :key-fn keyword)))

(defn- presented
  [{:keys [versionstamp changeset] :as entry}]
  (let-nom> [before (read-side (:before changeset))
             after (read-side (:after changeset))]
    (-> entry
        (dissoc :versionstamp :changeset)
        (assoc :audit-entry-id (store/entry-id versionstamp))
        (utility/assoc-some :changeset
                            (not-empty (utility/assoc-some {}
                                                           :before before
                                                           :after after))))))

(defn- cursors
  [opts]
  (let [{:keys [after before limit]} opts]
    (utility/assoc-some {:limit limit}
                        :after (store/entry-versionstamp after)
                        :before (store/entry-versionstamp before))))

(defn- page
  [found]
  (let [{:keys [entries before after]} found]
    (let-nom> [audit-entries (reduce (fn [audit-entries found-entry]
                                       (let [entry (presented found-entry)]
                                         (if (error/anomaly? entry)
                                           (reduced entry)
                                           (conj audit-entries entry))))
                                     []
                                     entries)]
      {:audit-entries audit-entries
       :before (some-> before
                       store/entry-id)
       :after (some-> after
                      store/entry-id)})))

(defn list-by-bank
  [txn bank-id opts]
  (let-nom> [found (store/list-by-bank
                    txn
                    bank-id
                    (-> (select-keys opts
                                     [:kinds :performer-kinds :request-id
                                      :performed-by :from :to])
                        (update :kinds distinct)
                        (update :performer-kinds distinct)
                        (update :performed-by distinct))
                    (cursors opts))]
    (page found)))

(defn list-by-subject
  [txn bank-id subject-id opts]
  (let-nom> [found
             (store/list-by-subject txn bank-id subject-id (cursors opts))]
    (page found)))

