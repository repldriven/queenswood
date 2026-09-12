(ns com.repldriven.queenswood.api.policy.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :refer
     [examples-registry]]))

(def PolicyNotFound
  {:value {:title "REJECTED"
           :type "policy/not-found"
           :status 404
           :detail "Policy not found"}})

(def registry (examples-registry [#'PolicyNotFound]))

(def PolicyId "pol.01kprbmgcj35ptc8npmybhh4t0")

(def Capability
  {:effect :effect-allow
   :reason "Allow opening cash accounts"
   :kind {:cash-account {:action :cash-account-action-open}}})

(def Limit
  {:bound {:kind {:max {:aggregate {:kind {:count {:value 100
                                                   :window
                                                   :time-window-instant}}}}}}
   :reason "Platform-restricted - max 100 customer organizations"
   :kind {:organization {:filters [{:type :organization-type-customer}]}}})

(def Policy
  {:policy-id PolicyId
   :name "Platform policy"
   :description "Platform policy - all capabilities and safety limits"
   :enabled true
   :category "restricted"
   :capabilities [Capability]
   :limits [Limit]
   :labels {"tier" "platform"}
   :created-at "2026-01-01T00:00:00Z"
   :updated-at "2026-01-01T00:00:00Z"})

(def PolicyList {:policies [Policy]})

(def Origin {:tier "platform" :policy-id PolicyId :name "Platform policy"})

(def EffectiveCapability (assoc Capability :origin Origin))

(def EffectiveLimit (assoc Limit :origin Origin))

(def EffectivePolicy
  {:capabilities [EffectiveCapability] :limits [EffectiveLimit]})
