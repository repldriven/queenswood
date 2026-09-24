(ns com.repldriven.queenswood.api.bank.examples
  (:require
    [com.repldriven.queenswood.api.access.examples :as access-examples]
    [com.repldriven.queenswood.api.balance.examples :as
     balance-examples]
    [com.repldriven.queenswood.api.me.examples :as me-examples]
    [com.repldriven.queenswood.api.party.examples :as
     party-examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [examples-registry]]
    [com.repldriven.queenswood.cash-account-api.interface :as
     cash-account-examples]))

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

(def ForeignBankRead
  {:value {:title "FORBIDDEN"
           :type "auth/forbidden"
           :status 403
           :detail "Token is not this bank's; retrieve only your own bank"}})

(def registry
  (examples-registry [#'BankNotFound #'BankInvalidStatus #'BankUnknownTier
                      #'ForeignBankRead]))

(def BankId (schema/id-examples "BankId"))

(def ClientSecret "k7DqGZ-Wt0aIqcPyQs8FdVx3y9rNJ4hLp1m6BvE-AtQ")

(def Owner
  (select-keys me-examples/Membership [:membership-id :user-id :name :email]))

(def Bank
  {:bank-id BankId
   :name "Galactic Bank"
   :status :test
   :sort-code "000001"
   :tier "micro"
   :created-at "2025-01-01T00:00:00Z"
   :updated-at "2025-01-01T00:00:00Z"
   :party (assoc party-examples/Party :type :organization)
   :accounts [(assoc cash-account-examples/CashAccount
                     :balances
                     [balance-examples/Balance])]
   :client-id BankId})

(def BankList {:items [(assoc Bank :owners [Owner])]})

(def CreateBankRequest
  {:name "Galactic Bank"
   :status :test
   :tier "micro"
   :currencies ["GBP"]
   :owner-email "zaphod@example.com"})

(def ChangeBankTierRequest {:tier "growth"})

(def ChangeBankStatusRequest {:status "live"})

(def CompanyBinding
  {:registry "uk-companies-house"
   :company-number "SC998137"
   :company-name "SIRIUS CYBERNETICS CORPORATION LTD"
   :company-status "active"
   :type "ltd"
   :jurisdiction "england-wales"
   :date-of-creation "2009-02-11"
   :registered-office-address
   "42 Improbability Way, London, QZ1 9ZX, United Kingdom"})

(def ^:private owner-invitation
  (assoc access-examples/Invitation
         :bank-id BankId
         :email "zaphod@example.com"
         :role :owner
         :reason "Owner of a new bank"
         :invited-by
         {:kind :operator :principal-id "queenswood-admin" :name "Queenswood"}))

(def CreateBankResponse
  (assoc Bank :client-secret ClientSecret :owner-invitation owner-invitation))
