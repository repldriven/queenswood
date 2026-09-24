(ns com.repldriven.queenswood.api.tier.routes
  (:require
    [com.repldriven.queenswood.api.tier.queries :as queries]))

(def routes
  [["/tiers"
    {:openapi {:tags ["Tiers"] :security [{"bearerAuth" ["admin"]}]}}
    [""
     {:get {:summary "List tiers"
            :openapi {:operationId "ListTiers"
                      :description
                      (str "Every tier a bank may be placed on, with its "
                           "description. A tier is a named set of policies "
                           "setting a bank's limits and capabilities.")}
            :responses {200 {:description "The tiers." :body [:ref "TierList"]}}
            :handler queries/list-tiers}}]]])
