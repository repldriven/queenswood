(ns com.repldriven.queenswood.api.reward.routes
  (:require
    [com.repldriven.queenswood.api.reward.queries :as queries]

    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer [ErrorResponse]]
    [com.repldriven.queenswood.reward-api.interface :refer [RewardNotFound]]))

(def ^:private list-query-schema
  [:map {:closed true} [:account-id [:ref "CashAccountId"]]])

(def routes
  [["/rewards"
    {:openapi {:tags ["Rewards"]
               :security [{"bearerAuth" ["org:viewer"]}]
               :parameters [shared.parameters/ref-bank-id-header]}}
    [""
     {:get {:summary "List the rewards paid or owed to an account"
            :openapi {:operationId "ListRewards"
                      :description
                      (str "At most one reward of each kind, and none for an "
                           "account whose product version offers none. A "
                           "reward stays `due` while paying it fails, most "
                           "often because the bank's own funds are short, "
                           "and the next rewards run tries again.")}
            :parameters {:query list-query-schema}
            :responses {200 {:description "The account's rewards."
                             :body [:ref "RewardList"]}}
            :handler queries/list-rewards}}]
    ["/{reward-id}"
     {:parameters {:path {:reward-id [:ref "RewardId"]}}
      :get {:summary "Retrieve a reward"
            :openapi {:operationId "RetrieveReward"
                      :description
                      (str "A reward of the bank, `paid` with the "
                           "transaction that paid it or `due` with the "
                           "reason the last attempt failed.")}
            :responses {200 {:description "The reward." :body [:ref "Reward"]}
                        404 (ErrorResponse [#'RewardNotFound])}
            :handler queries/get-reward}}]]])
