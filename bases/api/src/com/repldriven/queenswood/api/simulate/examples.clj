(ns com.repldriven.queenswood.api.simulate.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :refer
     [examples-registry]]))

(def SimulateInboundTransferRequest {:amount 1000 :currency "GBP"})

(def SimulateInboundTransferResponse
  {:account-id "acc.01kprbmgcj35ptc8npmybhh4s8"
   :transaction-id "txn.01kprbmgcj35ptc8npmybhh4sb"
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
           :detail (str "Bank has no"
                        " gl-account-code-cash-at-correspondent"
                        " ledger account in USD")}})

(def LedgerAccountClosed
  {:value {:title "REJECTED"
           :type ":ledger-account/closed"
           :status 409
           :detail "Ledger account is closed"}})

(def registry
  (examples-registry [#'BalanceNotFound #'InvalidAmount #'MissingCurrencyAccount
                      #'LedgerAccountClosed]))
