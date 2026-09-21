(ns com.repldriven.queenswood.reward-api.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :refer
     [examples-registry]]))

(def RewardNotFound
  {:value {:title "REJECTED"
           :type "reward/not-found"
           :status 404
           :detail "Reward not found"}})

(def RewardId "rwd.01kprbmgcj35ptc8npmybhh4t1")

(def Reward
  {:reward-id RewardId
   :bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :account-id "acc.01kprbmgcj35ptc8npmybhh4s8"
   :party-id "pty.01kprbmgcj35ptc8npmybhh4s9"
   :product-id "prd.01kprbmgcj35ptc8npmybhh4se"
   :version-id "prv.01kprbmgcj35ptc8npmybhh4sf"
   :kind "opening"
   :amount 5000
   :currency "GBP"
   :status "paid"
   :transaction-id "txn.01kprbmgcj35ptc8npmybhh4sb"
   :paid-at "2025-01-01T00:00:00Z"
   :created-at "2025-01-01T00:00:00Z"
   :updated-at "2025-01-01T00:00:00Z"})

(def RewardList {:value {:items [Reward]}})

(def registry (examples-registry [#'RewardNotFound]))
