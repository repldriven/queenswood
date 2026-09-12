(ns com.repldriven.queenswood.api.balance.components
  (:require
    [com.repldriven.queenswood.api.balance.coercion :as coercion]
    [com.repldriven.queenswood.api.balance.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :refer
     [components-registry]]))

(def BalanceType
  (coercion/balance-type-enum-schema {:json-schema/example "default"}))

(def BalanceStatus
  (coercion/balance-status-enum-schema {:json-schema/example "posted"}))

(def Balance
  [:map {:json-schema/example examples/Balance}
   [:account-id [:ref "CashAccountId"]]
   [:product-type [:ref "ProductType"]]
   [:balance-type [:ref "BalanceType"]]
   [:balance-status [:ref "BalanceStatus"]]
   [:currency [:ref "CurrencyCode"]]
   [:credit nat-int?]
   [:debit nat-int?]
   [:created-at [:ref "Timestamp"]]
   [:updated-at [:ref "Timestamp"]]])

(def BalanceList
  [:map {:json-schema/example examples/BalanceList}
   [:balances [:vector [:ref "Balance"]]]
   [:posted-balance [:ref "SignedAmount"]]
   [:available-balance [:ref "SignedAmount"]]])

(def BalanceProduct
  [:map {:closed true :json-schema/example examples/BalanceProduct}
   [:balance-type [:ref "BalanceType"]]
   [:balance-status [:ref "BalanceStatus"]]])

(def registry
  (components-registry [#'BalanceType #'BalanceStatus #'Balance #'BalanceList
                        #'BalanceProduct]))
