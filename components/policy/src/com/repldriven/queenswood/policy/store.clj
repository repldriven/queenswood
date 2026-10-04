(ns com.repldriven.queenswood.policy.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as util]))

(def ^:private store-name "policies")
(def ^:private bindings-store-name "policy-bindings")

(def ^:private stamp-name
  "One stamp over both stores, bumped by every policy and binding write,
  so a bank's effective policies can be kept until it moves."
  "policies")

(def transact fdb/transact)

;; Strip records here once at the read boundary: downstream
;; (`bank-policy/match`, etc.) compares via `=`, which treats
;; defrecords and content-equal maps as unequal.
(defn- pb->Policy
  [record]
  (util/record->map (schema/pb->Policy record)))

(defn- pb->PolicyBinding
  [record]
  (util/record->map (schema/pb->PolicyBinding record)))

(defn save-policy
  [txn policy]
  (fdb/transact
   txn
   (fn [txn]
     (let [saved (fdb/save-record (fdb/open txn store-name)
                                  (schema/Policy->java policy))]
       (fdb/bump-stamp txn stamp-name)
       saved))
   :policy/save
   "Failed to save policy"))

(defn get-policy
  [txn policy-id]
  (fdb/transact
   txn
   (fn [txn]
     (if-let [record (fdb/load-record (fdb/open txn store-name)
                                      policy-id)]
       (pb->Policy record)
       (error/reject :policy/not-found
                     {:message "Policy not found"
                      :policy-id policy-id})))
   :policy/get
   "Failed to load policy"))

(defn get-policies
  ([txn]
   (get-policies txn nil))
  ([txn opts]
   (fdb/transact
    txn
    (fn [txn]
      (let [{:keys [after before limit order]
             :or {limit 20 order :desc}}
            opts
            result (fdb/scan-records
                    (fdb/open txn store-name)
                    {:after after
                     :before before
                     :limit limit
                     :order order})]
        {:items (mapv pb->Policy (:records result))
         :before (:before result)
         :after (:after result)}))
    :policy/list
    "Failed to list policies")))

(defn get-policies-by-label
  [txn label-key label-value]
  (fdb/transact
   txn
   (fn [txn]
     (mapv pb->Policy
           (fdb/query-records-by-map-entry (fdb/open txn store-name)
                                           "Policy"
                                           "labels"
                                           label-key
                                           label-value
                                           {:index "Policy_by_label"})))
   :policy/list-by-label
   {:message "Failed to list policies by label"
    :label-key label-key
    :label-value label-value}))

(defn save-binding
  [txn binding]
  (fdb/transact
   txn
   (fn [txn]
     (let [saved (fdb/save-record (fdb/open txn bindings-store-name)
                                  (schema/PolicyBinding->java binding))]
       (fdb/bump-stamp txn stamp-name)
       saved))
   :policy-binding/save
   "Failed to save policy binding"))

(defn get-binding
  [txn binding-id]
  (fdb/transact
   txn
   (fn [txn]
     (if-let [record (fdb/load-record (fdb/open txn bindings-store-name)
                                      binding-id)]
       (pb->PolicyBinding record)
       (error/reject :policy-binding/not-found
                     {:message "Policy binding not found"
                      :binding-id binding-id})))
   :policy-binding/get
   "Failed to load policy binding"))

(defn delete-binding
  [txn binding-id]
  (fdb/transact
   txn
   (fn [txn]
     (let [deleted (fdb/delete-record (fdb/open txn bindings-store-name)
                                      binding-id)]
       (fdb/bump-stamp txn stamp-name)
       deleted))
   :policy-binding/delete
   "Failed to delete policy binding"))

(defn get-bindings
  ([txn]
   (get-bindings txn nil))
  ([txn opts]
   (fdb/transact
    txn
    (fn [txn]
      (let [{:keys [after before limit order]
             :or {limit 20 order :desc}}
            opts
            result (fdb/scan-records
                    (fdb/open txn bindings-store-name)
                    {:after after
                     :before before
                     :limit limit
                     :order order})]
        {:items (mapv pb->PolicyBinding (:records result))
         :before (:before result)
         :after (:after result)}))
    :policy-binding/list
    "Failed to list policy bindings")))

(defn get-bindings-for-bank
  "Returns all `PolicyBinding` records whose target is the given bank.
  Does a full scan and filters in memory — fine while binding
  cardinality is low; a `BankTarget` index is the natural follow-up
  once bindings grow."
  [txn bank-id]
  (fdb/transact
   txn
   (fn [txn]
     (let [result (fdb/scan-records
                   (fdb/open txn bindings-store-name)
                   {:limit 10000 :order :asc})]
       (->> (:records result)
            (mapv pb->PolicyBinding)
            (filterv (fn [b]
                       (= bank-id
                          (get-in b
                                  [:target :kind :bank
                                   :bank-id])))))))
   :policy-binding/list-by-bank
   {:message "Failed to list bindings for bank"
    :bank-id bank-id}))

(defn get-bindings-for-policy
  "Returns all `PolicyBinding` records for the given policy id. Does a
  full scan and filters in memory — fine while binding cardinality is
  low; a `policy_id` index is the natural follow-up once bindings grow.
  Used to guard archival (a bound policy can't be archived)."
  [txn policy-id]
  (fdb/transact
   txn
   (fn [txn]
     (let [result (fdb/scan-records
                   (fdb/open txn bindings-store-name)
                   {:limit 10000 :order :asc})]
       (->> (:records result)
            (mapv pb->PolicyBinding)
            (filterv (fn [b] (= policy-id (:policy-id b)))))))
   :policy-binding/list-by-policy
   {:message "Failed to list bindings for policy"
    :policy-id policy-id}))

(defn read-stamp
  [txn]
  (fdb/transact txn
                (fn [txn] (fdb/read-stamp txn stamp-name))
                :policy/stamp
                "Failed to read the policy stamp"))
