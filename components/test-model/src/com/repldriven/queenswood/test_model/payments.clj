(ns com.repldriven.queenswood.test-model.payments
  (:require
    [com.repldriven.queenswood.test-model.policies :as policies]
    [com.repldriven.queenswood.test-model.state :as state]

    [clojure.test.check.generators :as gen]))

(defn- operable?
  [state acct]
  (= :open (get-in state [:accounts acct :status])))

(defn- bank-of
  [state acct]
  (get-in state [:accounts acct :bank]))

(defn- within-daily?
  [state bank-id kind]
  (policies/within-count? state
                          bank-id
                          kind
                          nil
                          :time-window-daily
                          (get-in state [:banks bank-id :day-counts kind] 0)))

(defn- counted
  [state bank-id kind]
  (update-in state [:banks bank-id :day-counts kind] (fnil inc 0)))

(defn- bump-legs
  [state & accts]
  (reduce (fn [s a] (update-in s [:accounts a :transaction-legs] (fnil inc 0)))
          state
          accts))

(defn- apply-delta
  [state acct delta]
  (let [pre (state/balance state acct)
        post (+ pre delta)]
    (if (policies/permits? (:policies state) :available pre post)
      (-> state
          (assoc-in [:accounts acct :available] post)
          (bump-legs acct))
      state)))

(defn- transfer-between
  [state from to amount]
  (let [pre-from (state/balance state from)
        post-from (- pre-from amount)
        pre-to (state/balance state to)
        post-to (+ pre-to amount)]
    (if (and (policies/permits? (:policies state) :available pre-from post-from)
             (policies/permits? (:policies state) :available pre-to post-to))
      (-> state
          (assoc-in [:accounts from :available] post-from)
          (assoc-in [:accounts to :available] post-to)
          (bump-legs from to))
      state)))

(def inbound-transfer
  {:run? (fn [state] (seq (state/known-accounts state)))
   :args (fn [state]
           (gen/tuple (gen/elements (state/known-accounts state))
                      (gen/choose 1 10000)))
   :next-state
   (fn [state {[acct amount] :args}]
     (let [bank-id (bank-of state acct)
           marker (state/next-inbound-id state)
           lands? (and (operable? state acct)
                       (policies/permitted? state
                                            bank-id
                                            :inbound-payment
                                            :inbound-payment-action-receive)
                       (within-daily? state bank-id :inbound-payment))
           advanced (if lands? (apply-delta state acct amount) state)]
       (-> advanced
           (counted bank-id :inbound-payment)
           (update :inbound-payments conj marker)
           (update :next-inbound-id inc))))
   :valid? (fn [state {[acct] :args}] (contains? (:accounts state) acct))})

(def outbound-payment
  {:run? (fn [state] (seq (state/known-accounts state)))
   :args (fn [state]
           (gen/tuple (gen/elements (state/known-accounts state))
                      (gen/choose 1 10000)))
   :next-state
   (fn [state {args :args}]
     (let [[debtor creditor amount] (case (count args)
                                      2 [(first args) nil (second args)]
                                      3 args)]
       (if-not (and (pos? amount)
                    (policies/permitted? state
                                         (bank-of state debtor)
                                         :outbound-payment
                                         :outbound-payment-action-send)
                    (within-daily? state
                                   (bank-of state debtor)
                                   :outbound-payment))
         state
         (let [advanced (cond
                         (not (operable? state debtor))
                         state

                         (and creditor (operable? state creditor))
                         (transfer-between state debtor creditor amount)

                         :else
                         (apply-delta state debtor (- amount)))]
           (if (= advanced state)
             state
             (let [pmt-id (state/next-payment-id advanced)]
               (-> advanced
                   (counted (bank-of state debtor) :outbound-payment)
                   (update-in [:accounts debtor :transaction-legs] (fnil + 0) 2)
                   (assoc-in [:payments pmt-id]
                             (cond-> {:debtor debtor
                                      :amount amount
                                      :status :completed}
                                     creditor
                                     (assoc :creditor creditor)))
                   (update :next-payment-id inc))))))))
   :valid? (fn [state {args :args}]
             (let [[debtor maybe-creditor] args]
               (and (contains? (:accounts state) debtor)
                    (if (= 3 (count args))
                      (contains? (:accounts state) maybe-creditor)
                      true))))})

(defn- accounts-by-org
  [state]
  (group-by (fn [a] (get-in state [:accounts a :bank]))
            (state/known-accounts state)))

(def internal-transfer
  {:run? (fn [state]
           (boolean (some (fn [[_ accts]] (>= (count accts) 2))
                          (accounts-by-org state))))
   :args (fn [state]
           (let [groups (->> (accounts-by-org state)
                             vals
                             (filter (fn [accts] (>= (count accts) 2))))]
             (gen/let [accts (gen/elements groups)
                       from (gen/elements accts)
                       to (gen/such-that (fn [a] (not= a from))
                                         (gen/elements accts))
                       amount (gen/choose 1 10000)]
               [from to amount])))
   :next-state
   (fn [state {[from to amount currency] :args}]
     (let [bank-id (bank-of state from)
           moved (if (and (pos? amount)
                          (not= from to)
                          (operable? state from)
                          (operable? state to)
                          (= bank-id (bank-of state to))
                          (or (nil? currency) (= "GBP" currency))
                          (policies/permitted? state
                                               bank-id
                                               :internal-payment
                                               :internal-payment-action-submit)
                          (within-daily? state bank-id :internal-payment))
                   (transfer-between state from to amount)
                   state)]
       (if (= moved state) state (counted moved bank-id :internal-payment))))
   :valid? (fn [state {[from to] :args}]
             (and (contains? (:accounts state) from)
                  (contains? (:accounts state) to)))})
