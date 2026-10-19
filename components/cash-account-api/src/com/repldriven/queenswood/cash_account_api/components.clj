(ns com.repldriven.queenswood.cash-account-api.components
  (:require
    [com.repldriven.queenswood.cash-account-api.coercion :as coercion]
    [com.repldriven.queenswood.cash-account-api.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry]]
    [com.repldriven.queenswood.transaction-api.interface :as transaction-api]))

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

(def CashAccountOperationId
  (schema/id-schema "CashAccountOperationId"
                    "cao"
                    (:operation-id examples/CashAccountOperation)))

(def CashAccountOperationKind
  (coercion/operation-kind-enum-schema {:json-schema/example "suspend"}))

(def CashAccountOperationStatus
  (coercion/operation-status-enum-schema {:json-schema/example "completed"}))

(def CashAccountOperation
  "An operation asked of the account, and whether its payment provider
  carried it out."
  [:map {:json-schema/example examples/CashAccountOperation}
   [:bank-id [:ref "BankId"]]
   [:account-id [:ref "CashAccountId"]]
   [:operation-id [:ref "CashAccountOperationId"]]
   [:kind [:ref "CashAccountOperationKind"]]
   [:status [:ref "CashAccountOperationStatus"]]
   [:requested-on [:ref "BusinessDay"]]
   [:account-status [:ref "CashAccountStatus"]]
   [:refused-reason {:optional true} string?]
   [:nominated-account-id {:optional true} [:ref "CashAccountId"]]
   [:created-at [:ref "Timestamp"]]
   [:created-by [:ref "Actor"]]
   [:updated-at {:optional true} [:ref "Timestamp"]]
   [:updated-by {:optional true} [:ref "Actor"]]])

(def ^:private operation-keys
  (into [] (comp (filter vector?) (map first)) CashAccountOperation))

(defn ->operation-body
  [operation]
  (select-keys operation operation-keys))

(def CashAccountOperationList
  (schema/list-schema "CashAccountOperation" examples/CashAccountOperationList))

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
   [:status [:ref "CashAccountStatus"]]
   [:payment-addresses [:vector [:ref "PaymentAddress"]]]
   [:retired-payment-addresses {:optional true}
    [:vector [:ref "RetiredPaymentAddress"]]]
   [:bban {:optional true} [:ref "Bban"]]
   [:refused-reason {:optional true} string?]
   [:balances {:optional true} [:vector [:ref "Balance"]]]
   [:posted-balance {:optional true} [:ref "SignedAmount"]]
   [:available-balance {:optional true} [:ref "SignedAmount"]]
   [:transactions {:optional true} [:vector [:ref "Transaction"]]]
   [:operations {:optional true} [:vector [:ref "CashAccountOperation"]]]
   [:created-at [:ref "Timestamp"]]
   [:created-by [:ref "Actor"]]
   [:updated-at {:optional true} [:ref "Timestamp"]]
   [:updated-by {:optional true} [:ref "Actor"]]])

(def RetiredPaymentAddress
  [:map {:closed true}
   [:address [:ref "PaymentAddress"]]
   [:retired-at [:ref "Timestamp"]]])

(def cash-account-keys
  (into [] (comp (filter vector?) (map first)) CashAccount))

(defn ->body
  [account]
  (select-keys (cond-> account
                       (:transactions account)
                       (update :transactions
                               (partial mapv transaction-api/->body))

                       (:operations account)
                       (update :operations (partial mapv ->operation-body)))
               cash-account-keys))

(def CreateCashAccountRequest
  [:map {:json-schema/example examples/CreateCashAccountRequest}
   [:party-id [:ref "PartyId"]]
   [:name [:ref "Name"]]
   [:currency [:ref "Currency"]]
   [:product-id [:ref "ProductId"]]])

(def CreateCashAccountResponse [:ref "CashAccount"])

(def CashAccountList
  (schema/list-schema "CashAccount" examples/CashAccountList))

(def CloseCashAccountRequest
  [:map {:closed true :json-schema/example examples/CloseCashAccountRequest}
   [:nominated-account-id {:optional true} [:ref "CashAccountId"]]])

(def CloseCashAccountResponse [:ref "CashAccount"])

(def SuspendCashAccountResponse [:ref "CashAccount"])

(def ResumeCashAccountResponse [:ref "CashAccount"])

(def RotateCashAccountAddressResponse [:ref "CashAccount"])

(def registry
  (components-registry
   [#'CashAccountId #'ScanAddress #'PaymentAddress #'CashAccountStatus
    #'AccountType #'CashAccount #'RetiredPaymentAddress
    #'CreateCashAccountRequest #'CreateCashAccountResponse #'CashAccountList
    #'CloseCashAccountRequest #'CloseCashAccountResponse
    #'SuspendCashAccountResponse #'ResumeCashAccountResponse
    #'RotateCashAccountAddressResponse #'CashAccountOperationId
    #'CashAccountOperationKind #'CashAccountOperationStatus
    #'CashAccountOperation #'CashAccountOperationList]))

(def ^:private embedded-keys
  "The `CashAccount` keys a read route fills only when the caller asks
  for them with `embed`. A notification carries the account as a read
  route returns it without one: `->wire-body` carries neither, and a
  consumer wanting either follows the account's links."
  #{:balances :transactions :operations})

(def ^:private CashAccountWire
  (into []
        (remove (fn [entry]
                  (and (vector? entry) (embedded-keys (first entry)))))
        CashAccount))

(def ^:private encode-account
  (schema/api-encoder CashAccountWire (merge schema/registry registry)))

(defn ->wire-body
  [account]
  (encode-account (apply dissoc (->body account) embedded-keys)))

(def ^:private encode-operation
  (schema/api-encoder CashAccountOperation (merge schema/registry registry)))

(defn ->operation-wire-body
  [operation]
  (encode-operation (->operation-body operation)))
