(ns com.repldriven.queenswood.bank-api.examples
  (:require
    [com.repldriven.queenswood.access-api.interface :as access-api]
    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [examples-registry]]
    [com.repldriven.queenswood.balance-api.interface :as balance-api]
    [com.repldriven.queenswood.cash-account-api.interface :as
     cash-account-examples]
    [com.repldriven.queenswood.me-api.interface :as me-api]
    [com.repldriven.queenswood.party-api.interface :as party-api]))

(def BankNotFound
  {:value {:title "REJECTED"
           :type ":bank/not-found"
           :status 404
           :detail "Bank not found"}})

(def BankInvalidStatus
  {:value {:title "REJECTED"
           :type ":bank/invalid-status"
           :status 409
           :detail "Bank is not in a tier-changeable state"}})

(def BankUnknownTier
  {:value {:title "REJECTED"
           :type ":bank/unknown-tier"
           :status 422
           :detail "No policies found for tier"}})

(def BankUnnamed
  {:value {:title "FORBIDDEN"
           :type "auth/forbidden"
           :status 403
           :detail "Name the bank in the Bank-Id header"}})

(def BankUnknownProvider
  {:value {:title "REJECTED"
           :type ":bank/unknown-provider"
           :status 422
           :detail "The installation offers no payment provider other"}})

(def CompanyNotActive
  {:value {:title "REJECTED"
           :type ":bank/company-not-active"
           :status 422
           :detail "Only an active company can be bound to a bank"}})

(def CompanyRequired
  {:value {:title "REJECTED"
           :type ":bank/company-required"
           :status 422
           :detail "Name the company the bank is created for"}})

(def IdvUnsupportedCriteria
  {:value {:title "REJECTED"
           :type ":idv/unsupported-criteria"
           :status 422
           :detail "The identity provider cannot establish address"}})

(def OperatorFieldRefused
  {:value
   {:title "FORBIDDEN"
    :type "auth/forbidden"
    :status 403
    :detail
    "Only an operator chooses a bank's status, tier, currencies or owner"}})

(def registry
  (examples-registry [#'BankNotFound #'BankInvalidStatus #'BankUnknownTier
                      #'BankUnknownProvider #'CompanyNotActive #'CompanyRequired
                      #'IdvUnsupportedCriteria #'OperatorFieldRefused
                      #'BankUnnamed]))

(def BankId (schema/id-examples "BankId"))

(def ClientSecret "k7DqGZ-Wt0aIqcPyQs8FdVx3y9rNJ4hLp1m6BvE-AtQ")

(def Owner (select-keys me-api/Member [:member-id :user-id :name :email]))

;; nosemgrep: provider-name-in-domain — an example of the keys a bank records
(def BankProviders {:payment "modulr" :idv "zyphe"})

(def Bank
  {:bank-id BankId
   :name "Galactic Bank"
   :status :test
   :tier "micro"
   :providers BankProviders
   :created-at "2025-01-01T00:00:00Z"
   :updated-at "2025-01-01T00:00:00Z"
   :party (assoc party-api/Party :type :organization)
   :accounts
   [(assoc cash-account-examples/CashAccount :balances [balance-api/Balance])]
   :client-id BankId})

(def BankList {:items [(assoc Bank :owners [Owner])]})

(def CreateBankRequest
  {:name "Galactic Bank"
   :company-number "SC998137"
   :status :test
   :tier "micro"
   :currencies ["GBP"]
   :providers BankProviders
   :owner-email "zaphod@example.com"})

(def ChangeBankTierRequest {:tier "growth"})

(def ChangeBankStatusRequest {:status "live"})

(def CompanyBinding
  {:registry :uk-companies-house
   :company-number "SC998137"
   :name "SIRIUS CYBERNETICS CORPORATION LTD"
   :status "active"
   :company-type "ltd"
   :jurisdiction "england-wales"
   :incorporated-on "2009-02-11"
   :registered-office-address {:address-line-1 "42 Improbability Way"
                               :locality "London"
                               :postal-code "QZ1 9ZX"
                               :country "United Kingdom"}})

(def ^:private owner-invitation
  (assoc access-api/Invitation
         :bank-id BankId
         :email "zaphod@example.com"
         :role :owner
         :reason "Owner of a new bank"
         :invited-by
         {:kind :operator :principal-id "queenswood-admin" :name "Queenswood"}))

(def ChangeBankStatusResponse
  (assoc Bank :status :live :client-secret ClientSecret))

(def CreateBankResponse
  (assoc Bank
         :client-secret ClientSecret
         :company-binding CompanyBinding
         :owner-invitation owner-invitation))

;; nosemgrep: provider-name-in-domain — an example of the keys offered
(def Provider {:kind "payment" :providers ["form3" "modulr"] :default "modulr"})

;; nosemgrep: provider-name-in-domain — an example of the keys offered
(def ^:private idv-provider {:kind "idv" :providers ["zyphe"] :default "zyphe"})

(def ProviderList {:items [Provider idv-provider]})
