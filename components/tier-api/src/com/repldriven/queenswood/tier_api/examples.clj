(ns com.repldriven.queenswood.tier-api.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :refer
     [examples-registry]]))

(def registry (examples-registry []))

(def Tier
  {:tier "micro" :description "Micro tier policy - capabilities and limits"})

(def TierList {:items [Tier]})
