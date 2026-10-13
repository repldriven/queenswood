(ns com.repldriven.queenswood.simulate-api.components
  (:require
    [com.repldriven.queenswood.simulate-api.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :refer
     [components-registry]]))

(def SimulateInboundTransferRequest
  [:map
   {:closed true :json-schema/example examples/SimulateInboundTransferRequest}
   [:amount [:ref "PaymentMinorUnits"]]
   [:currency [:ref "Currency"]]])

(def SimulateInboundTransferResponse
  [:map {:json-schema/example examples/SimulateInboundTransferResponse}
   [:account-id [:ref "CashAccountId"]]
   [:amount [:ref "PaymentMinorUnits"]]
   [:currency [:ref "Currency"]]
   [:reference string?]])

(def registry
  (components-registry [#'SimulateInboundTransferRequest
                        #'SimulateInboundTransferResponse]))
