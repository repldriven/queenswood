(ns com.repldriven.queenswood.form3-adapter.cop.components)

(def CopAccountType
  [:enum {:json-schema/example "personal"}
   :account-type-personal :account-type-business])

(def CopMatchResult
  [:enum {:json-schema/example "match"}
   :match-result-match :match-result-close-match
   :match-result-no-match :match-result-unavailable])

(def CopAccount
  [:map
   {:json-schema/example {:sort-code "040004" :account-number "12345678"}}
   [:sort-code string?]
   [:account-number string?]])

(def CopOutboundRequest
  [:map
   {:json-schema/example {:creditor-name "Arthur Dent"
                          :account {:sort-code "040004"
                                    :account-number "12345678"}
                          :account-type :account-type-personal
                          :bank-id "bnk.01K6A3Z9X0"}}
   [:creditor-name string?]
   [:account [:ref "CopAccount"]]
   [:account-type [:ref "CopAccountType"]]
   [:bank-id {:optional true} [:maybe string?]]
   [:account-id {:optional true} [:maybe string?]]])

(def CopOutboundResponse
  [:map
   {:json-schema/example {:match-result :match-result-match}}
   [:match-result [:ref "CopMatchResult"]]
   [:actual-name {:optional true} [:maybe string?]]
   [:reason-code {:optional true} [:maybe string?]]
   [:reason {:optional true} [:maybe string?]]])

(def registry
  {"CopAccountType" CopAccountType
   "CopMatchResult" CopMatchResult
   "CopAccount" CopAccount
   "CopOutboundRequest" CopOutboundRequest
   "CopOutboundResponse" CopOutboundResponse})
