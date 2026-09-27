(ns com.repldriven.queenswood.modulr-webhook.components
  (:require
    [com.repldriven.queenswood.modulr-webhook.examples :as examples]

    [com.repldriven.mono.utility.interface :refer [vname]]))

(defn- registry
  [vars]
  (reduce (fn [m v] (assoc m (vname v) @v)) {} vars))

(def PartyIdentifier
  [:map
   {:json-schema/example examples/PartyIdentifier}
   [:Type {:optional true} [:maybe string?]]
   [:SortCode {:optional true} [:maybe string?]]
   [:AccountNumber {:optional true} [:maybe string?]]
   [:Iban {:optional true} [:maybe string?]]
   [:Bic {:optional true} [:maybe string?]]])

(def PartyDetail
  [:map
   {:json-schema/example examples/PartyDetail}
   [:Name {:optional true} [:maybe string?]]
   [:Identifier {:optional true} [:maybe [:ref "PartyIdentifier"]]]
   [:Address {:optional true} [:maybe map?]]])

(def SchemeInfo
  [:map
   {:json-schema/example examples/SchemeInfo}
   [:Id {:optional true} [:maybe string?]]
   [:Name {:optional true} [:maybe string?]]
   [:ResponseCode {:optional true} [:maybe string?]]])

(def PayinWebhook
  [:map
   {:json-schema/example examples/PayinWebhook}
   [:EventName [:= "PAYIN"]]
   [:EventId string?]
   [:EventTime {:optional true} [:maybe string?]]
   [:Type string?]
   [:PaymentId string?]
   [:TransactionId {:optional true} [:maybe string?]]
   [:AccountId string?]
   [:Amount [:or string? number?]]
   [:Currency string?]
   [:DateTime {:optional true} [:maybe string?]]
   [:PaymentAppliedTime {:optional true} [:maybe string?]]
   [:PayerName {:optional true} [:maybe string?]]
   [:Payer {:optional true} [:maybe [:ref "PartyDetail"]]]
   [:Payee {:optional true} [:maybe [:ref "PartyDetail"]]]
   [:PaymentReference {:optional true} [:maybe string?]]
   [:SchemeInfo {:optional true} [:maybe [:ref "SchemeInfo"]]]
   [:ReturnReason {:optional true} [:maybe string?]]
   [:OriginalSchemeId {:optional true} [:maybe string?]]
   [:SourceExternalReference {:optional true} [:maybe string?]]
   [:AccountExternalRef {:optional true} [:maybe string?]]
   [:OriginatedOverseas {:optional true} [:maybe boolean?]]])

(def PayoutWebhook
  [:map
   {:json-schema/example examples/PayoutWebhook}
   [:EventName [:= "PAYOUT"]]
   [:EventId string?]
   [:EventTime {:optional true} [:maybe string?]]
   [:Status string?]
   [:PaymentId string?]
   [:TransactionId {:optional true} [:maybe string?]]
   [:TransactionType {:optional true} [:maybe string?]]
   [:AccountId string?]
   [:Amount [:or string? number?]]
   [:DateTime {:optional true} [:maybe string?]]
   [:PaymentSubmittedTime {:optional true} [:maybe string?]]
   [:Reference {:optional true} [:maybe string?]]
   [:ExternalReference {:optional true} [:maybe string?]]
   [:SchemeInfo {:optional true} [:maybe [:ref "SchemeInfo"]]]])

(def ComplianceStatusWebhook
  [:map
   {:json-schema/example examples/ComplianceStatusWebhook}
   [:EventName [:= "PAYMENTCOMPLIANCESTATUS"]]
   [:EventId string?]
   [:EventTime {:optional true} [:maybe string?]]
   [:AccountBid string?]
   [:PaymentBid string?]
   [:CustomerBid {:optional true} [:maybe string?]]
   [:SchemeInfo {:optional true} [:maybe [:ref "SchemeInfo"]]]
   [:ComplianceStatus [:enum "HELD" "RELEASED" "RETURNED" "DECLINED"]]])

(def WebhookRejected
  [:map
   {:json-schema/example examples/WebhookRejected}
   [:type string?]
   [:title string?]
   [:status int?]
   [:detail {:optional true} [:maybe string?]]])

(def component-registry
  (registry [#'PartyIdentifier #'PartyDetail #'SchemeInfo #'PayinWebhook
             #'PayoutWebhook #'ComplianceStatusWebhook #'WebhookRejected]))

(def example-registry
  (reduce (fn [m v] (assoc m (vname v) {:value @v}))
          {}
          [#'examples/PayinWebhook #'examples/PayoutWebhook
           #'examples/ComplianceStatusWebhook #'examples/WebhookRejected]))
