(ns com.repldriven.queenswood.api.tier.components
  (:require
    [com.repldriven.queenswood.api.tier.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :refer
     [components-registry]]))

(def Tier
  [:map {:closed true :json-schema/example examples/Tier}
   [:tier [:ref "Name"]]
   [:description {:optional true} [:maybe string?]]])

(def TierList
  [:map {:json-schema/example examples/TierList}
   [:tiers [:vector [:ref "Tier"]]]])

(def registry (components-registry [#'Tier #'TierList]))
