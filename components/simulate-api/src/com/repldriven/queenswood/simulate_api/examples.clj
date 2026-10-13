(ns com.repldriven.queenswood.simulate-api.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :refer
     [examples-registry]]))

(def SimulateInboundTransferRequest {:amount 1000 :currency "GBP"})

(def SimulateInboundTransferResponse
  {:account-id "acc.01kprbmgcj35ptc8npmybhh4s8"
   :amount 1000
   :currency "GBP"
   :reference "Simulated inbound transfer"})

(def MissingCurrencyAccount
  {:value {:title "REJECTED"
           :type ":gl/missing-currency-account"
           :status 409
           :detail (str "Bank has no"
                        " ledger-account-code-cash-at-correspondent"
                        " ledger account in USD")}})

(def SimulateLiveBank
  {:value {:title "REJECTED"
           :type ":bank/invalid-status"
           :status 409
           :detail "Only a test bank can simulate"}})

(def registry (examples-registry [#'MissingCurrencyAccount #'SimulateLiveBank]))
