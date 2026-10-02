(ns com.repldriven.queenswood.form3-simulator.simulate.routes
  (:require
    [com.repldriven.queenswood.form3-simulator.simulate.handlers
     :as handlers]))

(def routes
  [["/simulate"
    {:openapi {:tags ["Simulate"]}}
    ["/inbound-payment"
     {:post
      {:summary "Send a payment to an account registered here"
       :description
       "Admitted before it settles: answered once the bank has completed
the admission's task, or once the deadline has passed."
       :openapi {:operationId "SimulateInboundPayment"}
       :parameters {:body [:ref "InboundPaymentRequest"]}
       :responses {202 {:body [:ref "InboundPaymentResponse"]} 404 {:body map?}}
       :handler handlers/inbound-payment}}]
    ["/inbound-settlement"
     {:post
      {:summary "Settle an inbound admitted with its settlement held"
       :description
       "An inbound sent with `settle: held` stays pending once the bank
admits it, until this confirms the admission."
       :openapi {:operationId "SimulateInboundSettlement"}
       :parameters {:body [:ref "InboundSettlementRequest"]}
       :responses {202 {:body [:ref "InboundPaymentResponse"]} 404 {:body map?}}
       :handler handlers/inbound-settlement}}]
    ["/outbound-return"
     {:post
      {:summary "Return a delivered payment to the bank that sent it"
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
     {:post {:summary "Fail the next account registration"
             :openapi {:operationId "SimulateOpenRefused"}
             :responses {204 {:description "The next registration fails."}}
             :handler handlers/open-refused}}]
    ["/close-refused"
     {:post {:summary "Fail the next account close"
             :openapi {:operationId "SimulateCloseRefused"}
             :responses {204 {:description "The next close fails."}}
             :handler handlers/close-refused}}]]])
