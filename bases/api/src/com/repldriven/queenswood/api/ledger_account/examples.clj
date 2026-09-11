(ns com.repldriven.queenswood.api.ledger-account.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :refer
     [examples-registry]]))

(def LedgerAccountNotFound
  {:value {:title "REJECTED"
           :type "ledger-account/not-found"
           :status 404
           :detail "Ledger account not found"}})

(def registry (examples-registry [#'LedgerAccountNotFound]))

(def LedgerAccount
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :account-id "led.01kprbmgcj35ptc8npmybhh4sa"
   :gl-code "2100"
   :name "Customer deposits - current"
   :currency "GBP"
   :gl-account-type :liability
   :gl-account-class :control
   :required :mandatory
   :status :open
   :posted-balance {:value 90000 :currency "GBP"}
   :created-at "2025-01-01T00:00:00Z"
   :updated-at "2025-01-01T00:00:00Z"})

(def LedgerAccountId (:account-id LedgerAccount))

(def LedgerAccountList
  {:ledger-accounts [LedgerAccount]
   :trial-balance [{:currency "GBP" :debit 90000 :credit 90000 :accounts 8}]})