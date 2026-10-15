(ns com.repldriven.queenswood.cash-account-product.core
  (:require
    [com.repldriven.queenswood.cash-account-product.domain :as domain]
    [com.repldriven.queenswood.cash-account-product.store :as store]

    [com.repldriven.queenswood.audit.interface :as audit]
    [com.repldriven.queenswood.cash-account-product-query.interface :as q]
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- get-policies
  ([txn bank-id opts]
   (or (:policies opts)
       (policy/get-effective-policies txn {:bank-id bank-id})))
  ([txn bank-id product-id opts]
   (or (:policies opts)
       (policy/get-effective-policies txn
                                      {:bank-id bank-id
                                       :cash-product-id product-id}))))

(defn- get-aggregates
  [txn bank-id product-type]
  (let-nom>
    [total (q/count-by-org txn bank-id)
     by-type (q/count-by-org-product-type txn bank-id product-type)]
    {:cash-account-product {#{:bank-id} total
                            #{:bank-id :product-type} by-type}}))

(defn- get-publish-aggregates
  "The product's versions published since the start of the calendar
  month `today` falls in."
  [txn bank-id product-id today]
  (let-nom> [n (q/count-published-since
                txn
                bank-id
                product-id
                (policy/period-start {:window :time-window-monthly
                                      :anchor :window-anchor-calendar}
                                     today
                                     nil))]
    {:cash-account-product {#{:bank-id :product-id :published-on} n}}))

(defn- or-already-created
  "On a uniqueness violation — a retried create carrying an already-seen
  idempotency-key — read the existing product version back and return
  it, so the caller gets the original resource instead of a duplicate
  draft product. Any other value passes through unchanged."
  [txn bank-id data result]
  (if (and (store/uniqueness-violation? result)
           (:idempotency-key data))
    (let-nom> [existing (q/find-version-by-idempotency-key
                         txn
                         bank-id
                         (:idempotency-key data))]
      (or existing result))
    result))

(defn- audited
  "Records in the bank's audit log the act taking a version from `before`
  to `after` that `opts` asks for, where it asks for one."
  [txn kind before after opts]
  (when (:audited? opts)
    (audit/record txn
                  {:bank-id (:bank-id (or after before))
                   :kind kind
                   :subject-kind :audit-subject-kind-product-version
                   :subject-id (:version-id (or after before))
                   :request-id (:request-id opts)
                   :before before
                   :after after
                   :performed-by (:actor opts)})))

(defn new-product
  ([txn bank-id data]
   (new-product txn bank-id data {}))
  ([txn bank-id data opts]
   (or-already-created
    txn
    bank-id
    data
    (store/transact
     txn
     (fn [txn]
       (let-nom>
         [policies (get-policies txn bank-id opts)
          template (q/get-template txn (:template-id data))
          aggregates (get-aggregates txn bank-id (:product-type template))
          version (domain/new-product bank-id
                                      template
                                      (assoc data :created-by (:actor opts))
                                      aggregates
                                      policies)
          _ (store/save-version txn version)
          _ (audited txn :audit-kind-product-created nil version opts)]
         version))))))

(defn open-draft
  ([txn bank-id product-id data]
   (open-draft txn bank-id product-id data {}))
  ([txn bank-id product-id data opts]
   (store/transact
    txn
    (fn [txn]
      (let-nom>
        [policies (get-policies txn bank-id product-id opts)
         versions (q/get-versions txn
                                  bank-id
                                  {:product-id product-id})
         template (q/get-template txn (:template-id (first versions)))
         version (domain/new-version bank-id
                                     product-id
                                     versions
                                     template
                                     (assoc data :created-by (:actor opts))
                                     policies)
         _ (store/save-version txn version)
         _ (audited txn :audit-kind-product-version-drafted nil version opts)]
        version)))))

(defn update-draft
  ([txn bank-id product-id version-id data]
   (update-draft txn bank-id product-id version-id data {}))
  ([txn bank-id product-id version-id data opts]
   (store/transact
    txn
    (fn [txn]
      (let-nom>
        [policies (get-policies txn bank-id product-id opts)
         existing (q/get-version txn bank-id product-id version-id)
         template (q/get-template txn (:template-id existing))
         version (domain/update-version existing
                                        template
                                        data
                                        policies
                                        (:actor opts))
         _ (store/save-version txn version)
         _ (audited txn
                    :audit-kind-product-version-updated
                    existing
                    version
                    opts)]
        version)))))

(defn discard-draft
  ([txn bank-id product-id version-id]
   (discard-draft txn bank-id product-id version-id {}))
  ([txn bank-id product-id version-id opts]
   (store/transact
    txn
    (fn [txn]
      (let-nom>
        [policies (get-policies txn bank-id product-id opts)
         existing (q/get-version txn bank-id product-id version-id)
         discarded (domain/discard existing policies (:actor opts))
         _ (store/delete-version txn discarded)
         _ (audited txn
                    :audit-kind-product-version-discarded
                    existing
                    nil
                    opts)]
        discarded)))))

(defn publish
  ([txn bank-id product-id version-id]
   (publish txn bank-id product-id version-id {}))
  ([txn bank-id product-id version-id opts]
   (store/transact
    txn
    (fn [txn]
      (let-nom>
        [today (utility/today)
         policies (get-policies txn bank-id product-id opts)
         existing (q/get-version txn bank-id product-id version-id)
         aggregates (get-publish-aggregates txn bank-id product-id today)
         published (domain/publish existing
                                   today
                                   aggregates
                                   policies
                                   (:payment-provider opts)
                                   (:actor opts))
         _ (store/save-version txn published)
         _ (audited txn
                    :audit-kind-product-version-published
                    existing
                    published
                    opts)]
        published)))))

(defn new-template
  [config data]
  (let [template (domain/new-template data)
        existing (when (:template-id data)
                   (q/get-template config (:template-id data)))
        template (cond-> template
                         (and (not (error/anomaly? existing))
                              (:created-at existing))
                         (assoc :created-at (:created-at existing)
                                :updated-at (:created-at template)))]
    (let-nom> [_ (store/save-template config template)]
      template)))
