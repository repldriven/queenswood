(ns com.repldriven.queenswood.reward-api.components
  (:require
    [com.repldriven.queenswood.reward-api.coercion :as coercion]
    [com.repldriven.queenswood.reward-api.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry]]
    [com.repldriven.queenswood.cash-account-api.interface :as
     cash-account-api]
    [com.repldriven.queenswood.transaction-api.interface :as
     transaction-api]))

(def RewardId (schema/id-schema "RewardId" "rwd" examples/RewardId))

(def RewardStatus
  (coercion/reward-status-enum-schema {:json-schema/example "paid"}))

(def RewardKind
  (coercion/reward-kind-enum-schema {:json-schema/example "opening"}))

(def Reward
  [:map {:json-schema/example examples/Reward}
   [:reward-id [:ref "RewardId"]]
   [:bank-id [:ref "BankId"]]
   [:account-id [:ref "CashAccountId"]]
   [:party-id [:ref "PartyId"]]
   [:product-id [:ref "ProductId"]]
   [:version-id [:ref "VersionId"]]
   [:kind [:ref "RewardKind"]]
   [:amount [:ref "MinorUnits"]]
   [:currency [:ref "Currency"]]
   [:status [:ref "RewardStatus"]]
   [:transaction-id {:optional true} [:maybe [:ref "TransactionId"]]]
   [:run-id {:optional true} [:maybe string?]]
   [:error {:optional true} [:maybe string?]]
   [:paid-at {:optional true} [:maybe [:ref "Timestamp"]]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at [:ref "Timestamp"]]])

(def RewardList
  [:map {:json-schema/example (:value examples/RewardList)}
   [:items [:vector [:ref "Reward"]]]])

(def registry
  (components-registry [#'RewardId #'RewardStatus #'RewardKind #'Reward
                        #'RewardList]))

(defn- declared-keys
  [component]
  (into [] (comp (filter vector?) (map first)) component))

(def ^:private reward-keys (declared-keys Reward))

(defn ->body
  [reward]
  (select-keys reward reward-keys))

(def ^:private wire-registry
  "What the wire encoder resolves a `$ref` against: the shared schemas,
  the account and transaction ids a reward names, and this brick's own."
  (merge schema/registry
         cash-account-api/registry
         transaction-api/registry
         registry))

(def ^:private encode (schema/api-encoder Reward wire-registry))

(defn ->wire-body
  [reward]
  (encode (->body reward)))
