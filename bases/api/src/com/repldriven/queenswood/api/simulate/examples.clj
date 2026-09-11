(ns com.repldriven.queenswood.api.simulate.examples
  (:require
    [com.repldriven.queenswood.api.schema :refer [examples-registry]]))

(def SimulateInboundTransferRequest
  {:account-id "acc.01kprbmgcj35ptc8npmybhh4s8" :amount 1000 :currency "GBP"})

(def SimulateInboundTransferResponse
  {:transaction-id "txn.01kprbmgcj35ptc8npmybhh4sb"
   :status "posted"
   :transaction-type "internal-transfer"
   :currency "GBP"
   :reference "Simulated inbound transfer"
   :created-at "2025-01-01T00:00:00Z"
   :updated-at "2025-01-01T00:00:00Z"
   :legs [{:leg-id "leg.01kprbmgcj35ptc8npmybhh4sc"
           :transaction-id "txn.01kprbmgcj35ptc8npmybhh4sb"
           :account-id "acc.01kprbmgcj35ptc8npmybhh4s8"
           :balance-type "suspense"
           :balance-status "posted"
           :side "debit"
           :amount 1000
           :currency "GBP"
           :created-at "2025-01-01T00:00:00Z"}
          {:leg-id "leg.01kprbmgcj35ptc8npmybhh4sd"
           :transaction-id "txn.01kprbmgcj35ptc8npmybhh4sb"
           :account-id "acc.01kprbmgcj35ptc8npmybhh4s9"
           :balance-type "default"
           :balance-status "posted"
           :side "credit"
           :amount 1000
           :currency "GBP"
           :created-at "2025-01-01T00:00:00Z"}]})

(def SimulateInterestRequest {:as-of-date "2026-03-26"})

(def SimulateInterestResponse
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :as-of-date "2026-03-26"
   :accounts-processed 5})

(def SettlementAccountNotFound
  {:value {:title "REJECTED"
           :type ":interest/no-settlement"
           :status 404
           :detail "No settlement account found"}})

(def BalanceNotFound
  {:value {:title "REJECTED"
           :type ":balance/not-found"
           :status 404
           :detail "Balance not found"}})

(def InvalidAmount
  {:value {:title "REJECTED"
           :type ":transaction/invalid-amount"
           :status 422
           :detail "Transaction amount must be positive"}})

(def MissingCurrencyAccount
  {:value {:title "REJECTED"
           :type ":gl/missing-currency-account"
           :status 409
           :detail (str "Bank has no ledger account for this"
                        " gl-account-code in this currency")}})

(def LedgerAccountClosed
  {:value {:title "REJECTED"
           :type ":ledger-account/closed"
           :status 409
           :detail "Ledger account is closed"}})

(def registry
  (examples-registry [#'SettlementAccountNotFound #'BalanceNotFound
                      #'InvalidAmount #'MissingCurrencyAccount
                      #'LedgerAccountClosed]))
