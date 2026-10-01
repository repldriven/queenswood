(ns com.repldriven.queenswood.test-model.accounts
  (:require
    [com.repldriven.queenswood.test-model.policies :as policies]
    [com.repldriven.queenswood.test-model.products :as products]
    [com.repldriven.queenswood.test-model.state :as state]

    [clojure.test.check.generators :as gen]))

(defn new-account
  [bank-id prod-id party-id]
  {:available 0
   :credit-carry 0
   :interest-accrued 0
   :status :open
   :bank bank-id
   :product prod-id
   :party party-id})

(def close-account
  {:run? (fn [state] (seq (state/open-accounts state)))
   :args (fn [state] (gen/tuple (gen/elements (state/open-accounts state))))
   :next-state (fn [state {[acct-id] :args}]
                 (if (or (zero? (state/balance state acct-id))
                         (policies/permitted?
                          state
                          (get-in state [:accounts acct-id :bank])
                          :cash-account
                          :cash-account-action-close-non-zero))
                   (assoc-in state [:accounts acct-id :status] :closed)
                   state))
   :valid? (fn [state {[acct-id] :args}]
             (and (= :open (get-in state [:accounts acct-id :status]))
                  (zero? (state/balance state acct-id))))})

(defn- may-open?
  [state bank-id]
  (and (policies/permitted? state
                            bank-id
                            :cash-account
                            :cash-account-action-open)
       (policies/within-count? state
                               bank-id
                               :cash-account
                               nil
                               :time-window-instant
                               (inc (count (get-in state
                                                   [:banks bank-id
                                                    :accounts]))))))

(defn- first-current-product
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
            (update :next-party-id inc))

        (may-open? state bank-id)
        (-> (assoc-in [:accounts acct-id]
                      (new-account bank-id prod-id party-id))
            (update-in [:banks bank-id :accounts] (fnil conj []) acct-id)
            (update :next-id inc)))))
   :valid? (fn [state {[bank-id] :args}] (contains? (:banks state) bank-id))})

(defn- opens-on?
  [state bank-id party-id prod-id]
  (let [party (get-in state [:parties party-id])
        product (get-in state [:products prod-id])]
    (and (= bank-id (:bank party))
         (= :active (:status party))
         (= bank-id (:bank product))
         (= :published (:status (peek (:versions product)))))))

(def open-account
  {:run? (constantly false)
   :next-state
   (fn [state {[bank-id party-id prod-id] :args}]
     (let [acct-id (state/next-id state)]
       (cond->
        (update state :next-id inc)

        (and (opens-on? state bank-id party-id prod-id)
             (may-open? state bank-id))
        (-> (assoc-in [:accounts acct-id]
                      (new-account bank-id prod-id party-id))
            (update-in [:banks bank-id :accounts] (fnil conj []) acct-id)))))})
