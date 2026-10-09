(ns com.repldriven.queenswood.cash-account-product-query.core
  (:require
    [com.repldriven.queenswood.cash-account-product-query.domain :as domain]
    [com.repldriven.queenswood.cash-account-product-query.store :as store]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

(defn get-template
  [txn template-id]
  (store/get-template txn template-id))

(defn list-templates
  [txn]
  (let-nom>
    [templates (store/get-templates txn)]
    (->> templates
         (remove domain/internal?)
         (sort-by :product-type)
         vec)))

(defn get-version
  [txn bank-id product-id version-id]
  (store/get-version txn bank-id product-id version-id))

(defn get-product
  [txn bank-id product-id]
  (let-nom>
    [versions (store/get-versions txn
                                  bank-id
                                  {:product-id product-id :limit 100})]
    {:product-id product-id
     :versions versions}))

(defn get-products
  "The public product listing for a bank. Internal products, the bank's
  own-funds house product, are excluded — a full-scan in-memory filter, fine while
  product cardinality per bank is low. Internal products remain
  reachable by id via `get-product`, which is what house-account
  opening and posting use."
  ([txn bank-id]
   (get-products txn bank-id nil))
  ([txn bank-id opts]
   (let-nom>
     [versions (store/get-versions txn bank-id opts)]
     {:items (->> versions
                  (partition-by :product-id)
                  (mapv (fn [vs]
                          {:product-id (:product-id (first vs))
                           :versions (vec vs)}))
                  (remove (fn [{:keys [versions]}]
                            (domain/internal? (first versions)))))})))
