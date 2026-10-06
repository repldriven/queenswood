(ns com.repldriven.queenswood.test-model.interest
  (:require
    [com.repldriven.queenswood.test-model.policies :as policies]
    [com.repldriven.queenswood.test-model.state :as state]

    [clojure.test.check.generators :as gen]))

(def ^:private micro-scale 1000000)

(defn daily-interest
  [{:keys [available credit-carry] :or {credit-carry 0}} interest-rate-bps]
  (when-not (zero? interest-rate-bps)
    (let [net available
          total-micro (+ (* net interest-rate-bps (quot micro-scale 10000))
                         (* credit-carry 365))
          daily-micro (quot total-micro 365)
          whole-units (quot daily-micro micro-scale)
          new-carry (rem daily-micro micro-scale)]
      {:whole-units whole-units :carry new-carry})))

(defn- customer-account?
  [state acct]
  (let [account (get-in state [:accounts acct])
        product (:product account)
        ptype (get-in state [:products product :product-type])]
    (and ptype
         (not (#{:settlement :internal} ptype))
         (= :open (:status account)))))

(defn- accrue-account
  [state customer-acct]
  (let [account (get-in state [:accounts customer-acct])
        product-id (:product account)
        rate (get-in state [:products product-id :interest-rate-bps] 0)
        result (daily-interest account rate)]
    (cond
     (nil? result)
     state

     (zero? (:whole-units result))
     (assoc-in state [:accounts customer-acct :credit-carry] (:carry result))

     :else
     (let [{:keys [whole-units carry]} result]
       (-> state
           (update-in [:accounts customer-acct :interest-accrued]
                      (fnil + 0)
                      whole-units)
           (assoc-in [:accounts customer-acct :credit-carry] carry)
           (update-in [:accounts customer-acct :transaction-legs]
                      (fnil inc 0)))))))

(defn- accrue-org
  [state bank-id]
  (let [org (get-in state [:banks bank-id])
        custs (filter (fn [a] (customer-account? state a))
                      (:accounts org))]
    (reduce accrue-account state custs)))

(defn- run-once
  [state bank-id date action f]
  (let [runs (get-in state [:banks bank-id :interest-runs action date] 0)]
    (if (and (policies/permitted? state bank-id :interest action)
             (policies/within-count? state
                                     bank-id
                                     :interest
                                     action
                                     :time-window-daily
                                     runs))
      (-> (f state bank-id)
          (assoc-in [:banks bank-id :interest-runs action date] (inc runs)))
      state)))

(def accrue-interest
  {:run? (fn [state] (seq (state/known-banks state)))
   :args (fn [state]
           (gen/tuple (gen/elements (state/known-banks state))
                      (gen/return (state/next-interest-date state))))
   :next-state (fn [state {[bank-id date] :args}]
                 (-> state
                     (run-once bank-id date :interest-action-accrue accrue-org)
                     (update :next-interest-date inc)))
   :valid? (fn [state {[bank-id] :args}] (contains? (:banks state) bank-id))})

(defn- capitalize-account
  [state customer-acct]
  (let [accrued (get-in state [:accounts customer-acct :interest-accrued] 0)]
    (if (zero? accrued)
      state
      (-> state
          (update-in [:accounts customer-acct :available] + accrued)
          (assoc-in [:accounts customer-acct :interest-accrued] 0)
          (update-in [:accounts customer-acct :transaction-legs]
                     (fnil + 0)
                     2)))))

(defn- capitalize-org
  [state bank-id]
  (let [org (get-in state [:banks bank-id])
        custs (filter (fn [a] (customer-account? state a))
                      (:accounts org))]
    (reduce capitalize-account state custs)))

(def capitalize-interest
  {:run? (fn [state] (seq (state/known-banks state)))
   :args (fn [state]
           (gen/tuple (gen/elements (state/known-banks state))
                      (gen/return (state/next-interest-date state))))
   :next-state
   (fn [state {[bank-id date] :args}]
     (-> state
         (run-once bank-id date :interest-action-capitalize capitalize-org)
         (update :next-interest-date inc)))
   :valid? (fn [state {[bank-id] :args}] (contains? (:banks state) bank-id))})
