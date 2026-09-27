(ns com.repldriven.queenswood.modulr-simulator.simulate.routes
  (:require
    [com.repldriven.queenswood.modulr-simulator.simulate.handlers
     :as handlers]))

(def routes
  [["/simulate"
    {:openapi {:tags ["Simulate"]}}
    ["/inbound-payment"
     {:post
      {:summary "Send a payment to an account the simulator holds"
       :description
       "The debtor name 6a41a29eafcf455493 is held for compliance
first, then released, or returned where outcome is return."
       :openapi {:operationId "SimulateInboundPayment"}
       :parameters {:body [:ref "InboundPaymentRequest"]}
       :responses {202 {:body [:map [:endToEndIdentification string?]]}
                   404 {:body map?}}
       :handler handlers/inbound-payment}}]
    ["/open-refused"
     {:post {:summary "Refuse the next account opening"
             :openapi {:operationId "SimulateOpenRefused"}
             :responses {204 {:description "The next opening is refused."}}
             :handler handlers/open-refused}}]
    ["/fund"
     {:post
      {:summary "Credit an account without notifying anyone"
       :description
       "For a rig that posts the ledger itself, as the scheme it
plays would have credited the account."
       :openapi {:operationId "SimulateFund"}
       :parameters {:body [:ref "FundRequest"]}
       :responses {204 {:description "Credited."} 404 {:body map?}}
       :handler handlers/fund}}]
    ["/balances"
     {:get {:summary "Every account the simulator holds, with its balance"
            :openapi {:operationId "SimulateBalances"}
            :responses {200 {:body [:map
                                    [:accounts [:vector [:ref "Account"]]]]}}
            :handler handlers/balances}}]]])
