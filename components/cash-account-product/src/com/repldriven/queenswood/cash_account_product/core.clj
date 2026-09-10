(ns com.repldriven.queenswood.cash-account-product.core
  (:require
    [com.repldriven.queenswood.cash-account-product.domain :as domain]
    [com.repldriven.queenswood.cash-account-product.store :as store]

    [com.repldriven.queenswood.cash-account-product-query.interface :as q]
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- get-policies
  ([txn bank-id opts]
   (or (:policies opts)
       (policy/get-effective-policies txn {:bank-id bank-id})))
  ([txn bank-id product-id opts]
   (or (:policies opts)
       (policy/get-effective-policies txn
                                      {:bank-id bank-id
                                       :cash-product-id product-id}))))

(defn- counts
  [txn bank-id product-type]
  (let-nom>
    [total (q/count-by-org txn bank-id)
     by-type (q/count-by-org-product-type txn bank-id product-type)]
    {:cash-account-product {#{:bank-id} total
                            #{:bank-id :product-type} by-type}}))

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

(defn new-product
  ([txn bank-id data]
   (new-product txn bank-id data {}))
  ([txn bank-id data opts]
   (or-already-created
    txn
    bank-id
    data
    (let-nom>
      [policies (get-policies txn bank-id opts)
       template (q/get-template txn (:template-id data))
       aggregates (counts txn bank-id (:product-type template))
       version (domain/new-product bank-id template data aggregates policies)
       _ (store/save-version txn version)]
      version))))

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
                                     data
                                     policies)
         _ (store/save-version txn version)]
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
         version (domain/update-version existing template data policies)
         _ (store/save-version txn version)]
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
         discarded (domain/discard existing policies)
         _ (store/save-version txn discarded)]
        discarded)))))

(defn publish
  ([txn bank-id product-id version-id]
   (publish txn bank-id product-id version-id {}))
  ([txn bank-id product-id version-id opts]
   (store/transact
    txn
    (fn [txn]
      (let-nom>
        [policies (get-policies txn bank-id product-id opts)
         existing (q/get-version txn bank-id product-id version-id)
         published (domain/publish existing policies)
         _ (store/save-version txn published)]
        published)))))

(defn new-template
  [config data]
  (let [template (domain/new-template data)
        existing (when (:template-id data)
                   (q/get-template config (:template-id data)))
        template (cond-> template
                         (and (not (error/anomaly? existing))
                              (:created-at existing))
                         (assoc :created-at (:created-at existing)))]
    (let-nom> [_ (store/save-template config template)]
      template)))
