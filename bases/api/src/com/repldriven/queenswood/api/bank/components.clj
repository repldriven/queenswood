(ns com.repldriven.queenswood.api.bank.components
  (:require
    [com.repldriven.queenswood.api.bank.coercion :as coercion]
    [com.repldriven.queenswood.api.bank.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :refer
     [components-registry]]))

(def BankStatus
  (coercion/bank-status-enum-schema {:json-schema/example "test"}))

(def CreateBankRequest
  [:map {:closed true :json-schema/example examples/CreateBankRequest}
   [:name [:ref "Name"]]
   [:status [:ref "BankStatus"]]
   [:tier [:ref "Name"]]
   [:currencies [:unique-vector {:min 1} [:ref "Currency"]]]
   [:owner-email {:optional true} [:ref "EmailAddress"]]])

(def Owner
  [:map
   {:json-schema/example examples/Owner
    :description
    "An owner of the bank: an active membership with the owner role, with
    the name and email its user record holds."}
   [:membership-id [:ref "MembershipId"]]
   [:user-id [:ref "UserId"]]
   [:name {:optional true} string?]
   [:email {:optional true} string?]])

(def Bank
  [:map {:json-schema/example examples/Bank}
   [:bank-id [:ref "BankId"]]
   [:name [:ref "Name"]]
   [:status [:ref "BankStatus"]]
   [:sort-code [:ref "SortCode"]]
   [:tier {:optional true} [:ref "Name"]]
   [:party [:ref "Party"]]
   [:accounts [:vector [:ref "CashAccount"]]]
   ;; Optional because `Bank` is also the body of the tier and status
   ;; changes, which carry no owners and which response coercion would
   ;; refuse if the field were required.
   [:owners {:optional true}
    [:vector
     {:description
      "The bank's owners. Present on every bank `GET /v1/banks` returns,
      and empty when the bank has no active owner."}
     [:ref "Owner"]]]
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
   [:owner-invitation {:optional true} [:ref "InvitationWithToken"]]
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
  (components-registry [#'BankStatus #'CreateBankRequest #'Owner #'Bank
                        #'BankList #'CompanyBinding #'CreateBankResponse
                        #'ChangeBankTierRequest #'ChangeBankTierResponse
                        #'ChangeBankStatusRequest #'ChangeBankStatusResponse]))
