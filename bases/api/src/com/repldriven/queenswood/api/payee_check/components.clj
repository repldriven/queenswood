(ns com.repldriven.queenswood.api.payee-check.components
  (:require
    [com.repldriven.queenswood.api.payee-check.coercion :as coercion]
    [com.repldriven.queenswood.api.payee-check.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry]]))

(def CheckId (schema/id-schema "CheckId" "chk" examples/CheckId))

(def PayeeCheckAccountType
  (coercion/account-type-enum-schema {:json-schema/example "personal"}))

(def MatchResult
  (coercion/match-result-enum-schema {:json-schema/example "match"}))

(def PayeeCheckAccount
  [:map
   [:sort-code [:ref "SortCode"]]
   [:account-number [:ref "AccountNumber"]]])

(def PayeeCheckRequest
  [:map
   {:json-schema/example examples/PayeeCheckRequest}
   [:creditor-name [:ref "Name"]]
   [:account [:ref "PayeeCheckAccount"]]
   [:account-type [:ref "PayeeCheckAccountType"]]])

(def PayeeCheckResult
  [:map
   [:match-result [:ref "MatchResult"]]
   [:actual-name {:optional true} [:maybe string?]]
   [:reason-code {:optional true} [:maybe string?]]
   [:reason {:optional true} [:maybe string?]]])

(def PayeeCheck
  [:map
   {:json-schema/example examples/PayeeCheck}
   [:check-id [:ref "CheckId"]]
   [:request [:ref "PayeeCheckRequest"]]
   [:result [:ref "PayeeCheckResult"]]
   [:created-at string?]
   [:expires-at string?]])

(def PayeeCheckLinks
  [:map
   [:next {:optional true} string?]
   [:prev {:optional true} string?]])

(def PayeeCheckList
  [:map
   {:json-schema/example examples/PayeeCheckList}
   [:items [:vector [:ref "PayeeCheck"]]]
   [:links {:optional true} [:ref "PayeeCheckLinks"]]])

(def registry
  (components-registry [#'CheckId #'PayeeCheckAccountType #'MatchResult
                        #'PayeeCheckAccount #'PayeeCheckRequest
                        #'PayeeCheckResult #'PayeeCheck #'PayeeCheckLinks
                        #'PayeeCheckList]))
