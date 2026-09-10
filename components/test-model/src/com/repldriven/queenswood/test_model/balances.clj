(ns com.repldriven.queenswood.test-model.balances
  (:require
    [com.repldriven.queenswood.test-model.products :as products]
    [com.repldriven.queenswood.test-model.state :as state]

    [clojure.test.check.generators :as gen]))

(def create-bank
  {:args (fn [_state] (gen/return []))
   :next-state (fn [state _command]
                 (let [bank-id (state/next-bank-id state)
                       acct-id (state/next-id state)
                       prod-id (state/next-product-id state)
                       party-id (state/next-party-id state)]
                   (-> state
                       (assoc-in [:accounts acct-id]
                                 {:available 0
                                  :credit-carry 0
                                  :interest-accrued 0
                                  :status :open
                                  :bank bank-id
                                  :product prod-id
                                  :party party-id})
                       (assoc-in [:banks bank-id]
                                 {:accounts [acct-id]
                                  :products [prod-id]
                                  :parties [party-id]
                                  :settlement-account acct-id})
                       (assoc-in [:products prod-id]
                                 {:bank bank-id
                                  ;; Post-CoA the scenario product is
                                  ;; a customer-current (the legacy
                                  ;; "settlement" product type retired
                                  ;; when GL accounts became
                                  ;; first-class).
                                  :product-type :current
                                  :interest-rate-bps 0
                                  :versions [(products/version :published 1)]})
                       (assoc-in
                        [:parties party-id]
                        {:bank bank-id :type :organization :status :active})
                       (update :next-id inc)
                       (update :next-bank-id inc)
                       (update :next-product-id inc)
                       (update :next-party-id inc))))})

(def close-account
  {:run? (fn [state] (seq (state/open-accounts state)))
   :args (fn [state] (gen/tuple (gen/elements (state/open-accounts state))))
   ;; Reality rejects a non-zero balance via
   ;; `:cash-account/non-zero-on-close` — predict a no-op, same convention
   ;; as `outbound-payment`'s non-positive-amount guard.
   :next-state (fn [state {[acct-id] :args}]
                 (if (zero? (state/balance state acct-id))
                   (assoc-in state [:accounts acct-id :status] :closed)
                   state))
   :valid? (fn [state {[acct-id] :args}]
             (and (= :open (get-in state [:accounts acct-id :status]))
                  (zero? (state/balance state acct-id))))})

(defn- first-current-product
  "First tracked **published** `:current` product on `bank-id`, or
  nil. Draft / discarded products can't back a cash-account open."
  [state bank-id]
  (some (fn [prod-id]
          (when (and (= :current
                        (get-in state [:products prod-id :product-type]))
                     (= :published
                        (:status (peek (get-in state
                                               [:products prod-id
                                                :versions])))))
            prod-id))
        (get-in state [:banks bank-id :products])))

(def create-customer
  "Macro command: open a customer (person party + cash account on a
  current product) on `bank-id`. If `prod-id` is supplied uses it;
  otherwise reuses the bank's first tracked current product or
  auto-creates one. Mirrors the `:create-customer` verb."
  {:run? (fn [state] (seq (state/known-banks state)))
   :args (fn [state] (gen/tuple (gen/elements (state/known-banks state))))
   :next-state
   (fn [state {[bank-id explicit-prod-id] :args}]
     (let [existing (first-current-product state bank-id)
           prod-id (or explicit-prod-id existing (state/next-product-id state))
           created-prod? (and (nil? explicit-prod-id) (nil? existing))
           party-id (state/next-party-id state)
           acct-id (state/next-id state)]
       (cond->
        state

        created-prod?
        (-> (assoc-in [:products prod-id]
                      {:bank bank-id
                       :product-type :current
                       :interest-rate-bps 0
                       :versions [(products/version :published 1)]})
            (update-in [:banks bank-id :products] (fnil conj []) prod-id)
            (update :next-product-id inc))

        true
        (-> (assoc-in [:parties party-id]
                      {:bank bank-id :type :person :status :active})
            (update-in [:banks bank-id :parties] (fnil conj []) party-id)
            (update :next-party-id inc)
            (assoc-in [:accounts acct-id]
                      {:available 0
                       :credit-carry 0
                       :interest-accrued 0
                       :status :open
                       :bank bank-id
                       :product prod-id
                       :party party-id})
            (update-in [:banks bank-id :accounts] (fnil conj []) acct-id)
            (update :next-id inc)))))
   :valid? (fn [state {[bank-id] :args}] (contains? (:banks state) bank-id))})
