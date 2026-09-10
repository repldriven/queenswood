(ns com.repldriven.queenswood.api.cash-account.examples
  (:require
    [com.repldriven.queenswood.api.schema :refer [examples-registry]]))

(def CashAccountNotFound
  {:value {:title "REJECTED"
           :type ":cash-account/not-found"
           :status 404
           :detail "Cash account not found"}})

(def ProductNotPublished
  {:value {:title "REJECTED"
           :type ":cash-account/product-not-published"
           :status 422
           :detail (str "No product version published for "
                        "prd.01kprbmgcj35ptc8npmybhh4se"
                        " effective on epoch day 20468")}})

(def InvalidCurrency
  {:value {:title "REJECTED"
           :type ":cash-account/invalid-currency"
           :status 422
           :detail "Currency not allowed for this product"}})

(def PartyNotFound
  {:value {:title "REJECTED"
           :type ":party/not-found"
           :status 404
           :detail "Party not found"}})

(def ProductNotFound
  {:value {:title "REJECTED"
           :type ":cash-account/product-not-found"
           :status 404
           :detail "Product not found"}})

(def CashAccountInvalidStatus
  {:value {:title "REJECTED"
           :type ":cash-account/invalid-status"
           :status 409
           :detail "Account is not in a valid state for this action"}})

(def CashAccountNonZeroBalance
  {:value {:title "REJECTED"
           :type ":cash-account/non-zero-on-close"
           :status 409
           :detail "Account has a non-zero balance"}})

(def registry
  (examples-registry [#'CashAccountNotFound #'ProductNotPublished
                      #'InvalidCurrency #'PartyNotFound #'ProductNotFound
                      #'CashAccountInvalidStatus #'CashAccountNonZeroBalance]))

(def CashAccount
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :account-id "acc.01kprbmgcj35ptc8npmybhh4s8"
   :party-id "pty.01kprbmgcj35ptc8npmybhh4s9"
   :name "Arthur Phillip Dent - Current Account"
   :currency "GBP"
   :product-id "prd.01kprbmgcj35ptc8npmybhh4se"
   :version-id "prv.01kprbmgcj35ptc8npmybhh4sf"
   :product-type :current
   :account-type :personal
   :account-status :opened
   :payment-addresses [{:scheme :scan
                        :scan {:sort-code "040004"
                               :account-number "12345678"}}]})

(def CashAccountId (:account-id CashAccount))

(def CashAccountList {:cash-accounts [CashAccount]})

(def CreateCashAccountRequest
  (select-keys CashAccount [:party-id :name :currency :product-id]))

