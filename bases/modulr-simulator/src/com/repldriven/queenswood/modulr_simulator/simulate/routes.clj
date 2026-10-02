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
    ["/outbound-return"
     {:post
      {:summary "Return a processed payment to the account it left"
       :description
       "As the beneficiary's bank does when it cannot apply a payment,
with the reason given as an ISO 20022 code, AC04 where none is."
       :openapi {:operationId "SimulateOutboundReturn"}
       :parameters {:body [:ref "OutboundReturnRequest"]}
       :responses {202 {:body [:map [:payment-id string?]]}
                   404 {:body map?}
                   409 {:body map?}}
       :handler handlers/outbound-return}}]
    ["/open-refused"
     {:post {:summary "Refuse the next account opening"
             :openapi {:operationId "SimulateOpenRefused"}
             :responses {204 {:description "The next opening is refused."}}
             :handler handlers/open-refused}}]
    ["/close-refused"
     {:post {:summary "Refuse the next account close"
             :openapi {:operationId "SimulateCloseRefused"}
             :responses {204 {:description "The next close is refused."}}
             :handler handlers/close-refused}}]
    ["/reissue-refused"
     {:post
      {:summary "Refuse the next address reissue"
       :description
       "The reissue's opening of the new account is refused, after the
old account has been blocked."
       :openapi {:operationId "SimulateReissueRefused"}
       :responses {204 {:description "The next reissue is refused."}}
       :handler handlers/reissue-refused}}]
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
