(ns com.repldriven.queenswood.modulr-simulator.components
  (:require
    [com.repldriven.mono.utility.interface :refer [vname]]))

(def Identifier
  [:map
   {:json-schema/example
    {:type "SCAN" :sortCode "040010" :accountNumber "00001457"}}
   [:type string?]
   [:sortCode {:optional true} [:maybe string?]]
   [:accountNumber {:optional true} [:maybe string?]]])

(def CreateAccountRequest
  [:map
   {:json-schema/example {:currency "GBP" :externalReference "acc-01K6A3Z9X0"}}
   [:currency {:optional true} [:maybe string?]]
   [:productCode {:optional true} [:maybe string?]]
   [:externalReference {:optional true} [:maybe string?]]])

(def Account
  [:map
   {:json-schema/example {:id "A0195C2D1E3F"
                          :balance "0.00"
                          :availableBalance "0.00"
                          :currency "GBP"
                          :status "ACTIVE"
                          :identifiers [{:type "SCAN"
                                         :sortCode "040010"
                                         :accountNumber "00001457"}]}}
   [:id string?]
   [:balance string?]
   [:availableBalance string?]
   [:currency string?]
   [:status string?]
   [:identifiers [:vector [:ref "Identifier"]]]
   [:customerId {:optional true} [:maybe string?]]
   [:externalReference {:optional true} [:maybe string?]]
   [:createdDate {:optional true} [:maybe string?]]])

(def Destination
  [:map
   [:type [:enum "SCAN" "ACCOUNT"]]
   [:id {:optional true} [:maybe string?]]
   [:sortCode {:optional true} [:maybe string?]]
   [:accountNumber {:optional true} [:maybe string?]]
   [:name {:optional true} [:maybe string?]]])

(def PaymentRequest
  [:map
   {:json-schema/example {:sourceAccountId "A0195C2D1E3F"
                          :destination {:type "SCAN"
                                        :sortCode "203002"
                                        :accountNumber "00004588"
                                        :name "Ford Prefect"}
                          :amount 12.5
                          :currency "GBP"
                          :reference "Towel"
                          :externalReference "pmt-01K6A3Z9X0"}}
   [:sourceAccountId string?]
   [:destination [:ref "Destination"]]
   [:amount number?]
   [:currency string?]
   [:reference {:optional true} [:maybe string?]]
   [:externalReference {:optional true} [:maybe string?]]
   [:nameCheck {:optional true} [:maybe map?]]])

(def Payment
  [:map
   {:json-schema/example {:id "P0195C2D1E3F"
                          :status "SUBMITTED"
                          :type "PAYOUT"
                          :externalReference "pmt-01K6A3Z9X0"}}
   [:id string?]
   [:status string?]
   [:type {:optional true} [:maybe string?]]
   [:externalReference {:optional true} [:maybe string?]]
   [:schemeId {:optional true} [:maybe string?]]
   [:createdDate {:optional true} [:maybe string?]]
   [:details {:optional true} [:maybe map?]]
   [:message {:optional true} [:maybe string?]]])

(def PaymentPage
  [:map
   [:content [:vector [:ref "Payment"]]]
   [:page int?]
   [:size int?]
   [:totalPages int?]
   [:totalSize int?]])

(def CreditRequest
  [:map
   {:json-schema/example
    {:accountId "A0195C2D1E3F"
     :amount 100
     :description "Sandbox funding"
     :type "PI_FAST"
     :payerDetail
     {:name "Sandbox"
      :identifier {:type "SCAN" :sortCode "000000" :accountNumber "00000000"}}}}
   [:accountId string?]
   [:amount number?]
   [:description string?]
   [:type {:optional true} [:maybe string?]]
   [:payerDetail {:optional true} [:maybe map?]]])

(def NotificationRequest
  [:map
   {:json-schema/example {:type "PAYIN"
                          :url "https://adapter.example/webhooks/payin"
                          :retry true
                          :secret "0123456789abcdef0123456789abcdef"
                          :hmacAlgorithm "hmac-sha1"}}
   [:type [:enum "PAYIN" "PAYOUT" "PAYMENT_COMPLIANCE_STATUS"]]
   [:url string?]
   [:retry boolean?]
   [:secret string?]
   [:hmacAlgorithm
    [:enum "hmac-sha1" "hmac-sha256" "hmac-sha384" "hmac-sha512"]]])

(def Notification
  [:map
   [:id string?]
   [:type string?]
   [:url string?]
   [:retry {:optional true} [:maybe boolean?]]
   [:secret {:optional true} [:maybe string?]]
   [:hmacAlgorithm {:optional true} [:maybe string?]]])

(def NameCheckRequest
  [:map
   {:json-schema/example {:paymentAccountId "A0195C2D1E3F"
                          :sortCode "203002"
                          :accountNumber "00004588"
                          :accountType "PERSONAL"
                          :name "Ford Prefect"}}
   [:paymentAccountId string?]
   [:sortCode string?]
   [:accountNumber string?]
   [:accountType [:enum "PERSONAL" "BUSINESS"]]
   [:name string?]
   [:secondaryAccountId {:optional true} [:maybe string?]]])

(def NameCheckResponse
  [:map
   [:id string?]
   [:result
    [:map
     [:code string?]
     [:name {:optional true} [:maybe string?]]]]])

(def Problem
  [:vector
   [:map
    [:code string?]
    [:message string?]
    [:field {:optional true} [:maybe string?]]
    [:errorCode {:optional true} [:maybe string?]]]])

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
   [:outcome {:optional true} [:enum "release" "return"]]])

(def OutboundReturnRequest
  [:map
   {:json-schema/example {:end-to-end-id "pmt.01k6a3z9x0" :reason-code "AC04"}}
   [:end-to-end-id string?]
   [:reason-code {:optional true} [:maybe string?]]])

(def FundRequest
  [:map
   {:json-schema/example {:bban "04001000001457" :amount 25.0}}
   [:bban string?]
   [:amount number?]])

(def registry
  (reduce (fn [m v] (assoc m (vname v) @v))
          {}
          [#'Identifier #'CreateAccountRequest #'Account #'Destination
           #'PaymentRequest #'Payment #'PaymentPage #'CreditRequest
           #'NotificationRequest #'Notification #'NameCheckRequest
           #'NameCheckResponse #'Problem #'InboundPaymentRequest
           #'OutboundReturnRequest #'FundRequest]))
