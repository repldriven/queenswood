(ns com.repldriven.queenswood.api.ledger-account.components
  (:require
    [com.repldriven.queenswood.api.ledger-account.coercion :as coercion]
    [com.repldriven.queenswood.api.ledger-account.examples :as examples]

    [com.repldriven.queenswood.api.schema :as schema :refer
     [components-registry]]))

(def LedgerAccountId
  (schema/id-schema "LedgerAccountId" "led" examples/LedgerAccountId))

(def GlAccountType
  (coercion/gl-account-type-enum-schema {:json-schema/example "liability"}))

(def GlAccountClass
  (coercion/gl-account-class-enum-schema {:json-schema/example "control"}))

(def Required
  (coercion/required-enum-schema {:json-schema/example "mandatory"}))

(def SubLedgerKind
  (coercion/sub-ledger-kind-enum-schema {:json-schema/example
                                         "cash-account-current"}))

(def LedgerAccountStatus
  (coercion/ledger-account-status-enum-schema {:json-schema/example "open"}))

(def LedgerAccount
  [:map {:json-schema/example examples/LedgerAccount}
   [:bank-id [:ref "BankId"]]
   [:account-id [:ref "LedgerAccountId"]]
   [:gl-code string?]
   [:name [:ref "Name"]]
   [:currency [:ref "CurrencyCode"]]
   [:gl-account-type [:ref "GlAccountType"]]
   [:gl-account-class [:ref "GlAccountClass"]]
   [:required [:ref "Required"]]
   [:sub-ledger-kind {:optional true} [:ref "SubLedgerKind"]]
   [:status [:ref "LedgerAccountStatus"]]
   ;; Present on the list (derived server-side); absent on the single-get.
   [:posted-balance {:optional true} [:ref "SignedAmount"]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at [:ref "Timestamp"]]])

(def TrialBalanceEntry
  "One currency's trial balance — total debits against total credits
  over its accounts. Equal (so the block balances) when the books tie;
  currencies never sum together, so there is no grand total."
  [:map
   [:currency [:ref "CurrencyCode"]]
   [:debit [:int {:json-schema/format "int64"}]]
   [:credit [:int {:json-schema/format "int64"}]]
   [:accounts nat-int?]])

(def LedgerAccountList
  [:map {:json-schema/example examples/LedgerAccountList}
   [:ledger-accounts [:vector [:ref "LedgerAccount"]]]
   [:trial-balance [:vector [:ref "TrialBalanceEntry"]]]])

(def LedgerBalance
  [:map
   [:account-id [:ref "LedgerAccountId"]]
   [:balance-type [:ref "BalanceType"]]
   [:balance-status [:ref "BalanceStatus"]]
   [:currency [:ref "CurrencyCode"]]
   [:credit nat-int?]
   [:debit nat-int?]
   [:created-at [:ref "Timestamp"]]
   [:updated-at [:ref "Timestamp"]]])

(def LedgerBalanceList
  [:map
   [:balances [:vector [:ref "LedgerBalance"]]]
   [:posted-balance [:ref "SignedAmount"]]
   [:available-balance [:ref "SignedAmount"]]])

(def registry
  (components-registry [#'LedgerAccountId #'GlAccountType #'GlAccountClass
                        #'Required #'SubLedgerKind #'LedgerAccountStatus
                        #'LedgerAccount #'TrialBalanceEntry #'LedgerAccountList
                        #'LedgerBalance #'LedgerBalanceList]))