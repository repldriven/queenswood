(ns com.repldriven.queenswood.api.bank.components
  (:require
    [com.repldriven.queenswood.api.bank.coercion :as coercion]
    [com.repldriven.queenswood.api.bank.examples :as examples]

    [com.repldriven.queenswood.api.schema :as schema :refer
     [components-registry]]))

(def BankId (schema/id-schema "BankId" "bnk" examples/BankId))

(def BankStatus
  (coercion/bank-status-enum-schema {:json-schema/example "test"}))

(def CreateBankRequest
  [:map {:closed true :json-schema/example examples/CreateBankRequest}
   [:name [:ref "Name"]]
   [:status [:ref "BankStatus"]]
   [:tier [:ref "Name"]]
   [:currencies [:unique-vector {:min 1} [:ref "Currency"]]]])

(def Bank
  [:map {:json-schema/example examples/Bank}
   [:bank-id [:ref "BankId"]]
   [:name [:ref "Name"]]
   [:status [:ref "BankStatus"]]
   [:sort-code [:ref "SortCode"]]
   [:tier {:optional true} [:ref "Name"]]
   [:party [:ref "Party"]]
   [:accounts [:vector [:ref "CashAccount"]]]
   [:client-id [:ref "BankId"]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at [:ref "Timestamp"]]])

(def BankList
  [:map {:json-schema/example examples/BankList}
   [:banks [:vector [:ref "Bank"]]]])

(def CompanyBinding
  "The confirmed legal-entity snapshot a bank is bound to (onboarding
  via a company registry). Absent for admin-provisioned banks."
  [:map {:json-schema/example examples/CompanyBinding}
   [:registry string?]
   [:company-number string?]
   [:company-name string?]
   [:company-status string?]
   [:type {:optional true} string?]
   [:jurisdiction {:optional true} string?]
   [:date-of-creation {:optional true} string?]
   [:registered-office-address {:optional true} string?]])

(def CreateBankResponse
  [:map {:json-schema/example examples/CreateBankResponse}
   [:bank-id [:ref "BankId"]]
   [:name [:ref "Name"]]
   [:status [:ref "BankStatus"]]
   [:sort-code [:ref "SortCode"]]
   ;; Required here but optional on `Bank`: creation rejects an
   ;; unmatched tier, so a bank this release just made always carries
   ;; one, while `Bank` describes any bank on record — including those
   ;; stored before the tier was demanded.
   [:tier [:ref "Name"]]
   [:party [:ref "Party"]]
   [:accounts [:vector [:ref "CashAccount"]]]
   [:client-id [:ref "BankId"]]
   [:client-secret string?]
   [:company-binding {:optional true} [:ref "CompanyBinding"]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at [:ref "Timestamp"]]])

(def ChangeBankTierRequest
  [:map {:closed true :json-schema/example examples/ChangeBankTierRequest}
   [:tier [:ref "Name"]]])

(def ChangeBankTierResponse [:ref "Bank"])

(def ChangeBankStatusRequest
  [:map {:closed true :json-schema/example examples/ChangeBankStatusRequest}
   [:status [:ref "BankStatus"]]])

(def ChangeBankStatusResponse [:ref "Bank"])

(def registry
  (components-registry [#'BankId #'BankStatus #'CreateBankRequest #'Bank
                        #'BankList #'CompanyBinding #'CreateBankResponse
                        #'ChangeBankTierRequest #'ChangeBankTierResponse
                        #'ChangeBankStatusRequest #'ChangeBankStatusResponse]))
