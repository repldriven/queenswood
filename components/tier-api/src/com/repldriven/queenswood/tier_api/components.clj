(ns com.repldriven.queenswood.tier-api.components
  (:require
    [com.repldriven.queenswood.tier-api.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :refer
     [components-registry list-schema]]))

(def Tier
  [:map {:closed true :json-schema/example examples/Tier}
   [:tier [:ref "Name"]]
   [:description {:optional true} [:maybe string?]]])

(def TierList (list-schema "Tier" examples/TierList))

(def registry (components-registry [#'Tier #'TierList]))
