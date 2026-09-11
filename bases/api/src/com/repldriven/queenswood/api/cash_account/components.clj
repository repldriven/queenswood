(ns com.repldriven.queenswood.api.cash-account.components
  (:require
    [com.repldriven.queenswood.api.cash-account.coercion :as coercion]
    [com.repldriven.queenswood.api.cash-account.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry]]))

(def CashAccountId
  (schema/id-schema "CashAccountId" "acc" examples/CashAccountId))

(def ScanAddress
  [:map {:closed true}
   [:sort-code [:ref "SortCode"]]
   [:account-number [:ref "AccountNumber"]]])

(def PaymentAddress
  "A scheme and at most one of a SCAN pair or a string value. The
  record declares the two variants as sibling optional fields rather
  than a proto `oneof`, so the shape a response carries is
  `{scheme, scan, value}` with the unused variant absent."
  [:map {:closed true}
   [:scheme [:ref "PaymentAddressScheme"]]
   [:scan {:optional true} [:maybe [:ref "ScanAddress"]]]
   [:value {:optional true} [:maybe string?]]])

(def CashAccountStatus
  (coercion/cash-account-status-enum-schema {:json-schema/example "opened"}))

(def AccountType
  (coercion/account-type-enum-schema {:json-schema/example "personal"}))

(def CashAccount
  [:map {:json-schema/example examples/CashAccount}
   [:bank-id [:ref "BankId"]]
   [:account-id [:ref "CashAccountId"]]
   [:party-id [:ref "PartyId"]]
   [:name [:ref "Name"]]
   [:currency [:ref "Currency"]]
   [:product-id [:ref "ProductId"]]
   [:version-id [:ref "VersionId"]]
   [:product-type [:ref "ProductType"]]
   [:account-type [:ref "AccountType"]]
   [:account-status [:ref "CashAccountStatus"]]
   [:payment-addresses [:vector [:ref "PaymentAddress"]]]
   [:retired-payment-addresses {:optional true}
    [:vector [:ref "RetiredPaymentAddress"]]]
   [:bban {:optional true} [:ref "Bban"]]
   [:balances {:optional true} [:vector [:ref "Balance"]]]
   [:posted-balance {:optional true} [:ref "SignedAmount"]]
   [:available-balance {:optional true} [:ref "SignedAmount"]]
   [:transactions {:optional true} [:vector [:ref "Transaction"]]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at [:ref "Timestamp"]]])

(def RetiredPaymentAddress
  [:map {:closed true}
   [:address [:ref "PaymentAddress"]]
   [:retired-at [:ref "Timestamp"]]])

(def cash-account-keys
  "Every key `CashAccount` declares. The read routes project a stored
  account through these, so a record field the component does not
  declare cannot reach a response body."
  (into [] (comp (filter vector?) (map first)) CashAccount))

(def CreateCashAccountRequest
  [:map {:json-schema/example examples/CreateCashAccountRequest}
   [:party-id [:ref "PartyId"]]
   [:name [:ref "Name"]]
   [:currency [:ref "Currency"]]
   [:product-id [:ref "ProductId"]]])

(def CreateCashAccountResponse [:ref "CashAccount"])

(def CashAccountList
  [:map {:json-schema/example examples/CashAccountList}
   [:cash-accounts [:vector [:ref "CashAccount"]]]
   [:links {:optional true}
    [:map
     [:next {:optional true} string?]
     [:prev {:optional true} string?]]]])

(def CloseCashAccountResponse [:ref "CashAccount"])

(def SuspendCashAccountResponse [:ref "CashAccount"])

(def ResumeCashAccountResponse [:ref "CashAccount"])

(def RotateCashAccountAddressResponse [:ref "CashAccount"])

(def registry
  (components-registry
   [#'CashAccountId #'ScanAddress #'PaymentAddress #'CashAccountStatus
    #'AccountType #'CashAccount #'RetiredPaymentAddress
    #'CreateCashAccountRequest #'CreateCashAccountResponse #'CashAccountList
    #'CloseCashAccountResponse #'SuspendCashAccountResponse
    #'ResumeCashAccountResponse #'RotateCashAccountAddressResponse]))
