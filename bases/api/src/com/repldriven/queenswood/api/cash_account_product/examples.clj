(ns com.repldriven.queenswood.api.cash-account-product.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [examples-registry]]))

(def ProductNotFound
  {:value {:title "REJECTED"
           :type "cash-account-products/product-not-found"
           :status 404
           :detail "Product not found"}})

(def VersionNotFound
  {:value {:title "REJECTED"
           :type "cash-account-products/version-not-found"
           :status 404
           :detail "Version not found"}})

(def DraftAlreadyExists
  {:value {:title "REJECTED"
           :type "cash-account-products/draft-already-exists"
           :status 409
           :detail "A draft already exists"}})

(def VersionImmutable
  {:value {:title "REJECTED"
           :type "cash-account-products/version-immutable"
           :status 409
           :detail "Version is not a draft and cannot be modified"}})

(def CurrencyNotAllowed
  {:value {:title "REJECTED"
           :type "cash-account-products/currency-not-allowed"
           :status 422
           :detail "Currency not allowed for this product-type"}})

(def TemplateMismatch
  {:value {:title "REJECTED"
           :type "cash-account-products/template-mismatch"
           :status 422
           :detail "Template does not match the product's template"}})

(def registry
  (examples-registry [#'ProductNotFound #'VersionNotFound #'DraftAlreadyExists
                      #'VersionImmutable #'CurrencyNotAllowed
                      #'TemplateMismatch]))

(def ProductId (schema/id-examples "ProductId"))
(def VersionId (schema/id-examples "VersionId"))
(def TemplateId "tpl.00000000000000000000000001")

(def CashAccountProductVersion
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :product-id ProductId
   :version-id VersionId
   :version-number 1
   :status :draft
   :name "Current Account"
   :template-id TemplateId
   :product-type :current
   :balance-sheet-side :liability
   :allowed-currencies ["GBP"]
   :balance-products [{:balance-type :default :balance-status :posted}]
   :allowed-payment-address-schemes [:scan]
   :interest-rate-bps 0
   :effective-from "2025-01-01"
   :created-at "2025-01-01T00:00:00Z"
   :updated-at "2025-01-01T00:00:00Z"})

(def CashAccountProduct
  {:product-id ProductId :versions [CashAccountProductVersion]})

(def CashAccountProductList {:items [CashAccountProduct]})

(def CashAccountProductRequest
  {:name "Current Account"
   :template-id TemplateId
   :currency "GBP"
   :interest-rate-bps 0
   :effective-from "2025-01-01"})

(def CashAccountProductTemplate
  {:template-id TemplateId
   :name "Current Account"
   :product-type :current
   :balance-sheet-side :liability
   :allowed-currencies ["GBP"]
   :balance-products [{:balance-type :default :balance-status :posted}]
   :allowed-payment-address-schemes [:scan]})

(def CashAccountProductTemplateList
  {:items [CashAccountProductTemplate
           (assoc CashAccountProductTemplate
                  :template-id "tpl.00000000000000000000000002"
                  :name "Savings account"
                  :product-type :savings)
           (assoc CashAccountProductTemplate
                  :template-id "tpl.00000000000000000000000003"
                  :name "Term deposit"
                  :product-type :term-deposit)]})
