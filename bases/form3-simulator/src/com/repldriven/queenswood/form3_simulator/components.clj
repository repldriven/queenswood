(ns com.repldriven.queenswood.form3-simulator.components
  (:require
    [com.repldriven.mono.utility.interface :refer [vname]]))

(def ResourceRequest
  [:map
   {:json-schema/example {:data {:id "7826c3cb-d6fd-41d0-b187-dc23ba928772"
                                 :type "payments"
                                 :attributes {:amount "25.00"
                                              :currency "GBP"}}}}
   [:data
    [:map
     [:id string?]
     [:type {:optional true} [:maybe string?]]
     [:version {:optional true} [:maybe int?]]
     [:attributes {:optional true} [:maybe map?]]
     [:relationships {:optional true} [:maybe map?]]]]])

(def ResourceResponse [:map [:data [:ref "Resource"]]])

(def ResourceListResponse [:map [:data [:vector [:ref "Resource"]]]])

(def ApiError
  [:map
   {:json-schema/example {:error_code "01a0e3cb-b4af-7eb0-a1c3-12c26c98fc41"
                          :error_message "The payment does not exist"}}
   [:error_code string?]
   [:error_message string?]])

(def InboundPaymentRequest
  [:map
   {:json-schema/example {:bban "04001000001457"
                          :amount 25.0
                          :currency "GBP"
                          :reference "Towel"
                          :debtor-name "Ford Prefect"}}
   [:bban string?]
   [:amount number?]
   [:currency string?]
   [:reference {:optional true} [:maybe string?]]
   [:debtor-name {:optional true} [:maybe string?]]
   [:outcome {:optional true} [:maybe string?]]])

(def InboundPaymentResponse
  [:map
   [:endToEndIdentification string?]
   [:admission-status string?]
   [:status-reason {:optional true} [:maybe string?]]])

(def OutboundReturnRequest
  [:map
   {:json-schema/example {:end-to-end-id "pmt.01k6a3z9x0" :reason-code "AC04"}}
   [:end-to-end-id string?]
   [:reason-code {:optional true} [:maybe string?]]])

(def registry
  (reduce (fn [m v] (assoc m (vname v) @v))
          {}
          [#'ResourceRequest #'ResourceResponse #'ResourceListResponse
           #'ApiError #'InboundPaymentRequest #'InboundPaymentResponse
           #'OutboundReturnRequest]))
