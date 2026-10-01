(ns com.repldriven.queenswood.test-model.banks
  (:require
    [com.repldriven.queenswood.test-model.accounts :as accounts]
    [com.repldriven.queenswood.test-model.products :as products]
    [com.repldriven.queenswood.test-model.state :as state]

    [clojure.test.check.generators :as gen]))

(def create-bank
  {:freq 1
   :args (fn [_state] (gen/return []))
   :next-state
   (fn [state _command]
     (let [bank-id (state/next-bank-id state)
           acct-id (state/next-id state)
           prod-id (state/next-product-id state)
           party-id (state/next-party-id state)]
       (-> state
           (assoc-in [:accounts acct-id]
                     (accounts/new-account bank-id prod-id party-id))
           (assoc-in [:banks bank-id]
                     {:accounts [acct-id]
                      :products [prod-id]
                      :parties [party-id]
                      :settlement-account acct-id
                      :policies (if-let [tier (:tier-policy state)]
                                  [tier]
                                  [])})
           (assoc-in [:products prod-id]
                     {:bank bank-id
                      :product-type :current
                      :interest-rate-bps 0
                      :versions [(products/version :published 1)]})
           (assoc-in [:parties party-id]
                     {:bank bank-id :type :organization :status :active})
           (update :next-id inc)
           (update :next-bank-id inc)
           (update :next-product-id inc)
           (update :next-party-id inc))))})
