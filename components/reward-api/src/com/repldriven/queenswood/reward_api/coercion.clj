(ns com.repldriven.queenswood.reward-api.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private reward-status-enum
  (coercion/enum-coercion {"due" :reward-status-due "paid" :reward-status-paid}
                          :reward-status-unknown))

(def reward-status-enum-schema (:enum-schema reward-status-enum))

(def ^:private reward-kind-enum
  (coercion/enum-coercion {"opening" :reward-kind-opening}
                          :reward-kind-unknown))

(def reward-kind-enum-schema (:enum-schema reward-kind-enum))
