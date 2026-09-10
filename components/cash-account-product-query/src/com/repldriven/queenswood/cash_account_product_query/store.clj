(ns com.repldriven.queenswood.cash-account-product-query.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error]))

;; must match cash-account-product.store store-names — same FDB stores
(def ^:private store-name "cash-account-products")
(def ^:private templates-store-name "cash-account-product-templates")

(def transact fdb/transact)

(defn get-template
  [txn template-id]
  (fdb/transact
   txn
   (fn [txn]
     (if-let [record (fdb/load-record (fdb/open txn templates-store-name)
                                      template-id)]
       (schema/pb->CashAccountProductTemplate record)
       (error/reject :cash-account-product/template-not-found
                     {:message "Template not found"
                      :template-id template-id})))
   :cash-account-product/get-template
   "Failed to load template"))

(defn get-templates
  [txn]
  (fdb/transact
   txn
   (fn [txn]
     (mapv schema/pb->CashAccountProductTemplate
           (:records (fdb/scan-records (fdb/open txn templates-store-name)
                                       {:limit 1000 :order :asc}))))
   :cash-account-product/list-templates
   "Failed to list templates"))

(defn get-version
  [txn bank-id product-id version-id]
  (fdb/transact
   txn
   (fn [txn]
     (if-let [record (fdb/load-record (fdb/open txn store-name)
                                      bank-id
                                      product-id
                                      version-id)]
       (schema/pb->CashAccountProduct record)
       (error/reject :cash-account-product/version-not-found
                     {:message "Version not found"
                      :bank-id bank-id
                      :product-id product-id
                      :version-id version-id})))
   :cash-account-product/get-version
   "Failed to load product version"))

(defn find-version-by-idempotency-key
  [txn bank-id idempotency-key]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/query-record-compound
              (fdb/open txn store-name)
              "CashAccountProduct"
              [["bank_id" bank-id]
               ["idempotency_key" idempotency-key]]
              {:index "CashAccountProduct_by_idempotency_key"})
             schema/pb->CashAccountProduct))
   :cash-account-product/find-by-idempotency-key
   "Failed to find product version by idempotency key"))

(defn count-by-org
  [txn bank-id]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/count-groups (fdb/open txn store-name)
                       "CashAccountProduct_count_by_bank"
                       [bank-id]))
   :cash-account-product/count-by-org
   {:message "Failed to count products by org"
    :bank-id bank-id}))

(defn count-by-org-product-type
  [txn bank-id product-type]
  (fdb/transact
   txn
   (fn [txn]
     ;; Group key is [bank_id, product_type, product_id]; counting groups
     ;; under the [bank_id, product_type] prefix yields distinct products
     ;; of that type (not their versions).
     (fdb/count-groups (fdb/open txn store-name)
                       "CashAccountProduct_count_by_bank_product_type"
                       [bank-id (schema/product-type->int product-type)]))
   :cash-account-product/count-by-org-product-type
   {:message "Failed to count products by org/product-type"
    :bank-id bank-id
    :product-type product-type}))

(defn get-versions
  ([txn bank-id]
   (get-versions txn bank-id nil))
  ([txn bank-id opts]
   (fdb/transact
    txn
    (fn [txn]
      (let [{:keys [product-id limit order]
             :or {limit 1000 order :desc}}
            opts
            prefix (if product-id [bank-id product-id] [bank-id])
            versions
            (mapv schema/pb->CashAccountProduct
                  (:records (fdb/scan-records (fdb/open txn store-name)
                                              {:prefix prefix
                                               :limit limit
                                               :order order})))]
        (if (and product-id (empty? versions))
          (error/reject :cash-account-product/product-not-found
                        {:message "Product not found"
                         :bank-id bank-id
                         :product-id product-id})
          versions)))
    :cash-account-product/list-versions
    "Failed to list product versions")))
