(ns com.repldriven.queenswood.bank-api.components
  (:require
    [com.repldriven.queenswood.bank-api.coercion :as coercion]
    [com.repldriven.queenswood.bank-api.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :refer
     [components-registry list-schema]]))

(def BankStatus
  (coercion/bank-status-enum-schema {:json-schema/example "test"}))

(def BankProviders
  [:map-of
   {:json-schema/example examples/BankProviders
    :description
    "The key of the provider of each kind the bank runs on, by kind. A
    bank's providers are chosen when it is created and never change."}
   keyword? string?])

(def CreateBankRequest
  [:map
   {:closed true
    :json-schema/example examples/CreateBankRequest
    :description
    "A person names the company the bank is for and its providers, and
    becomes its owner. An operator may name a company and providers too,
    may choose the status, tier and currencies, which default to test,
    micro and GBP, and may name an owner by email. A kind of provider
    left out takes the installation's default."}
   [:name [:ref "Name"]]
   [:company-number {:optional true} string?]
   [:providers {:optional true} [:ref "BankProviders"]]
   [:status {:optional true} [:ref "BankStatus"]]
   [:tier {:optional true} [:ref "Name"]]
   [:currencies {:optional true} [:unique-vector {:min 1} [:ref "Currency"]]]
   [:owner-email {:optional true} [:ref "EmailAddress"]]])

(def Owner
  [:map
   {:json-schema/example examples/Owner
    :description
    "An owner of the bank: an active member with the owner role, with
    the name and email its user record holds."}
   [:member-id [:ref "MemberId"]]
   [:user-id [:ref "UserId"]]
   [:name {:optional true} string?]
   [:email {:optional true} string?]])

(def Bank
  [:map {:json-schema/example examples/Bank}
   [:bank-id [:ref "BankId"]]
   [:name [:ref "Name"]]
   [:status [:ref "BankStatus"]]
   [:tier {:optional true} [:ref "Name"]]
   [:providers [:ref "BankProviders"]]
   [:party [:ref "Party"]]
   [:accounts [:vector [:ref "CashAccount"]]]
   ;; Optional because `Bank` is also the body of the tier change,
   ;; which carries no owners and which response coercion would
   ;; refuse if the field were required.
   [:owners {:optional true}
    [:vector
     {:description
      "The bank's owners. Present on every bank `GET /v1/banks` returns,
      and empty when the bank has no active owner."}
     [:ref "Owner"]]]
   [:client-id [:ref "BankId"]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at {:optional true} [:ref "Timestamp"]]])

(def BankList (list-schema "Bank" examples/BankList))

(def CompanyBinding
  "The confirmed legal-entity snapshot a bank is bound to (onboarding
  via a company registry). Absent for admin-provisioned banks."
  [:map {:json-schema/example examples/CompanyBinding}
   [:registry [:ref "CompanyRegistry"]]
   [:company-number string?]
   [:name string?]
   [:status string?]
   [:company-type string?]
   [:jurisdiction {:optional true} string?]
   [:incorporated-on {:optional true} [:ref "BusinessDay"]]
   [:registered-office-address {:optional true}
    [:ref "RegisteredOfficeAddress"]]])

(def CreateBankResponse
  [:map {:json-schema/example examples/CreateBankResponse}
   [:bank-id [:ref "BankId"]]
   [:name [:ref "Name"]]
   [:status [:ref "BankStatus"]]
   ;; Required here but optional on `Bank`: creation rejects an
   ;; unmatched tier, so a bank this release just made always carries
   ;; one, while `Bank` describes any bank on record — including those
   ;; stored before the tier was demanded.
   [:tier [:ref "Name"]]
   [:providers [:ref "BankProviders"]]
   [:party [:ref "Party"]]
   [:accounts [:vector [:ref "CashAccount"]]]
   [:client-id [:ref "BankId"]]
   [:client-secret string?]
   [:owner-invitation {:optional true} [:ref "Invitation"]]
   [:member {:optional true} [:ref "Member"]]
   [:company-binding {:optional true} [:ref "CompanyBinding"]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at {:optional true} [:ref "Timestamp"]]])

(def ChangeBankTierRequest
  [:map {:closed true :json-schema/example examples/ChangeBankTierRequest}
   [:tier [:ref "Name"]]])

(def ChangeBankTierResponse [:ref "Bank"])

(def ChangeBankStatusRequest
  [:map {:closed true :json-schema/example examples/ChangeBankStatusRequest}
   [:status [:ref "BankStatus"]]])

(def ChangeBankStatusResponse
  [:map
   {:json-schema/example examples/ChangeBankStatusResponse
    :description
    "The bank with its new status, and the client secret issued in place
    of the one it had."}
   [:bank-id [:ref "BankId"]]
   [:name [:ref "Name"]]
   [:status [:ref "BankStatus"]]
   [:tier {:optional true} [:ref "Name"]]
   [:providers [:ref "BankProviders"]]
   [:party [:ref "Party"]]
   [:accounts [:vector [:ref "CashAccount"]]]
   [:client-id [:ref "BankId"]]
   [:client-secret string?]
   [:company-binding {:optional true} [:ref "CompanyBinding"]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at {:optional true} [:ref "Timestamp"]]])

(def Provider
  [:map
   {:closed true
    :json-schema/example examples/Provider
    :description
    "A kind of provider the installation offers: the key of each provider
    of that kind, and the default a bank takes where its create names
    none."}
   [:kind string?]
   [:providers [:vector string?]]
   [:default string?]])

(def ProviderList (list-schema "Provider" examples/ProviderList))

(def registry
  (components-registry [#'BankStatus #'BankProviders #'CreateBankRequest #'Owner
                        #'Bank #'BankList #'CompanyBinding #'CreateBankResponse
                        #'ChangeBankTierRequest #'ChangeBankTierResponse
                        #'ChangeBankStatusRequest #'ChangeBankStatusResponse
                        #'Provider #'ProviderList]))
