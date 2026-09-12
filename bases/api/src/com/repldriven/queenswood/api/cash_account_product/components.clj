(ns com.repldriven.queenswood.api.cash-account-product.components
  (:require
    [com.repldriven.queenswood.api.cash-account-product.coercion :as coercion]
    [com.repldriven.queenswood.api.cash-account-product.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry]]))

(def TemplateId (schema/id-schema "TemplateId" "tpl" examples/TemplateId))

(def BalanceSheetSide
  (coercion/balance-sheet-side-enum-schema {:json-schema/example "liability"}))

(def VersionStatus
  (coercion/version-status-enum-schema {:json-schema/example "draft"}))

(def CashAccountProductRequest
  [:map {:closed true :json-schema/example examples/CashAccountProductRequest}
   [:name [:ref "Name"]]
   [:template-id [:ref "TemplateId"]]
   [:currency [:ref "Currency"]]
   [:interest-rate-bps {:optional true} [:ref "SignedBasisPoints"]]
   [:effective-from [:ref "BusinessDay"]]
   [:effective-to {:optional true} [:maybe [:ref "BusinessDay"]]]])

(def CashAccountProductDraftRequest
  [:map {:closed true :json-schema/example examples/CashAccountProductRequest}
   [:name [:ref "Name"]]
   [:template-id {:optional true} [:ref "TemplateId"]]
   [:currency [:ref "Currency"]]
   [:interest-rate-bps {:optional true} [:ref "SignedBasisPoints"]]
   [:effective-from [:ref "BusinessDay"]]
   [:effective-to {:optional true} [:maybe [:ref "BusinessDay"]]]])

(def CashAccountProductVersion
  [:map {:json-schema/example examples/CashAccountProductVersion}
   [:bank-id [:ref "BankId"]]
   [:product-id [:ref "ProductId"]]
   [:version-id [:ref "VersionId"]]
   [:version-number int?]
   [:status [:ref "VersionStatus"]]
   [:name {:optional true} [:ref "Name"]]
   [:template-id {:optional true} [:ref "TemplateId"]]
   [:product-type [:ref "ProductType"]]
   [:balance-sheet-side [:ref "BalanceSheetSide"]]
   [:allowed-currencies [:unique-vector-lax {:min 1} [:ref "Currency"]]]
   [:balance-products [:unique-vector-lax {:min 1} [:ref "BalanceProduct"]]]
   [:allowed-payment-address-schemes
    [:unique-vector-lax {:min 1} [:ref "PaymentAddressScheme"]]]
   [:interest-rate-bps {:optional true} [:ref "SignedBasisPoints"]]
   [:effective-from {:optional true} [:maybe [:ref "BusinessDay"]]]
   [:effective-to {:optional true} [:maybe [:ref "BusinessDay"]]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at [:ref "Timestamp"]]
   [:discarded-at {:optional true} [:maybe [:ref "Timestamp"]]]])

(def CashAccountProduct
  [:map {:json-schema/example examples/CashAccountProduct}
   [:product-id [:ref "ProductId"]]
   [:versions [:vector [:ref "CashAccountProductVersion"]]]])

(def CashAccountProductListLinks
  [:map
   [:next {:optional true} string?]
   [:prev {:optional true} string?]])

(def CashAccountProductList
  [:map {:json-schema/example examples/CashAccountProductList}
   [:items [:vector [:ref "CashAccountProduct"]]]
   [:links {:optional true} [:ref "CashAccountProductListLinks"]]])

(def CashAccountProductTemplate
  [:map {:json-schema/example examples/CashAccountProductTemplate}
   [:template-id [:ref "TemplateId"]]
   [:name {:optional true} [:ref "Name"]]
   [:product-type [:ref "ProductType"]]
   [:balance-sheet-side [:ref "BalanceSheetSide"]]
   [:allowed-currencies [:unique-vector-lax {:min 1} [:ref "Currency"]]]
   [:balance-products [:unique-vector-lax {:min 1} [:ref "BalanceProduct"]]]
   [:allowed-payment-address-schemes
    [:vector [:ref "PaymentAddressScheme"]]]])

(def CashAccountProductTemplateList
  [:map {:json-schema/example examples/CashAccountProductTemplateList}
   [:items [:vector [:ref "CashAccountProductTemplate"]]]])

(def registry
  (components-registry
   [#'TemplateId #'BalanceSheetSide #'VersionStatus #'CashAccountProductRequest
    #'CashAccountProductDraftRequest #'CashAccountProductVersion
    #'CashAccountProduct #'CashAccountProductListLinks #'CashAccountProductList
    #'CashAccountProductTemplate #'CashAccountProductTemplateList]))
