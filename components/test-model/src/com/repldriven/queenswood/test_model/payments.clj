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

(defn- day-count
  [state bank-id kind]
  (get-in state [:banks bank-id :day-counts kind] 0))

(defn- within-daily?
  ([state bank-id kind] (within-daily? state bank-id kind 0))
  ([state bank-id kind excluded]
   (policies/within-count? state
                           bank-id
                           kind
                           nil
                           :time-window-daily
                           (- (day-count state bank-id kind) excluded))))

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

(defn- receives?
  [state acct excluded]
  (let [bank-id (bank-of state acct)]
    (and (policies/permitted? state
                              bank-id
                              :inbound-payment
                              :inbound-payment-action-receive)
         (within-daily? state bank-id :inbound-payment excluded))))

(defn- matching
  [state e2e acct amount]
  (first (keep-indexed (fn [i record]
                         (when (= [e2e acct amount]
                                  ((juxt :e2e :acct :amount) record))
                           i))
                       (:inbound-records state))))

(defn- record-inbound
  [state record]
  (-> state
      (update :inbound-records (fnil conj []) record)
      (counted (bank-of state (:acct record)) :inbound-payment)))

(defn- credited
  [state acct amount]
  (let [credited (apply-delta state acct amount)]
    [credited (if (= credited state) :suspended :settled)]))

(defn- settle-inbound
  [state acct amount e2e stx]
  (let [held (matching state e2e acct amount)]
    (cond
     (and stx (some (fn [r] (= stx (:stx r))) (:inbound-records state)))
     state

     (not (operable? state acct))
     (record-inbound state
                     {:e2e e2e
                      :acct acct
                      :amount amount
                      :stx stx
                      :status :suspended})

     (and held (= :held (get-in state [:inbound-records held :status])))
     (if (receives? state acct 1)
       (let [[state' status] (credited state acct amount)]
         (assoc-in state' [:inbound-records held :status] status))
       (assoc-in state [:inbound-records held :status] :suspended))

     :else
     (let [[state' status] (if (receives? state acct 0)
                             (credited state acct amount)
                             [state :suspended])]
       (record-inbound state'
                       {:e2e e2e
                        :acct acct
                        :amount amount
                        :stx stx
                        :status status})))))

(def inbound-transfer
  {:run? (fn [state] (seq (state/known-accounts state)))
   :args (fn [state]
           (gen/tuple (gen/elements (state/known-accounts state))
                      (gen/choose 1 10000)))
   :next-state (fn [state {[acct amount e2e] :args}]
                 (let [marker (state/next-inbound-id state)
                       e2e (or e2e marker)]
                   (-> state
                       (settle-inbound acct amount e2e e2e)
                       (update :inbound-payments conj marker)
                       (update :next-inbound-id inc))))
   :valid? (fn [state {[acct] :args}] (contains? (:accounts state) acct))})

(def hold-inbound
  {:run? (constantly false)
   :next-state
   (fn [state {[acct amount e2e] :args}]
     (let [e2e (or e2e (keyword (str "held-" (:next-inbound-id state))))]
       (cond->
        (-> state
            (assoc-in [:last-holds acct] {:e2e e2e :amount amount})
            (update :next-inbound-id inc))

        (and (operable? state acct) (nil? (matching state e2e acct amount)))
        (record-inbound {:e2e e2e :acct acct :amount amount :status :held}))))})

(def release-inbound
  {:run? (constantly false)
   :next-state (fn [state {[acct] :args}]
                 (if-let [{:keys [e2e amount]} (get-in state
                                                       [:last-holds acct])]
                   (settle-inbound state acct amount e2e nil)
                   state))})

(defn- submits?
  "True where `debtor`'s bank accepts a submission of `amount`: a positive
  amount the bank's policies permit sending within their daily count,
  from an open account."
  [state debtor amount]
  (let [bank-id (bank-of state debtor)]
    (and (pos? amount)
         (policies/permitted? state
                              bank-id
                              :outbound-payment
                              :outbound-payment-action-send)
         (within-daily? state bank-id :outbound-payment)
         (operable? state debtor))))

(defn- record-payment
  [state payment legs]
  (let [{:keys [debtor]} payment
        pmt-id (state/next-payment-id state)]
    (-> state
        (counted (bank-of state debtor) :outbound-payment)
        (update-in [:accounts debtor :transaction-legs] (fnil + 0) legs)
        (assoc-in [:payments pmt-id] payment)
        (update :next-payment-id inc))))

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
       (if-not (submits? state debtor amount)
         state
         (let [advanced (if (and creditor (operable? state creditor))
                          (transfer-between state debtor creditor amount)
                          (apply-delta state debtor (- amount)))]
           (if (= advanced state)
             state
             (record-payment advanced
                             (cond-> {:debtor debtor
                                      :amount amount
                                      :status :completed}
                                     creditor
                                     (assoc :creditor creditor))
                             2))))))
   :valid? (fn [state {args :args}]
             (let [[debtor maybe-creditor] args]
               (and (contains? (:accounts state) debtor)
                    (if (= 3 (count args))
                      (contains? (:accounts state) maybe-creditor)
                      true))))})

(def outbound-payment-refused
  {:run? (fn [state] (seq (state/known-accounts state)))
   :args (fn [state]
           (gen/tuple (gen/elements (state/known-accounts state))
                      (gen/choose 1 10000)))
   :next-state
   (fn [state {[debtor amount] :args}]
     (let [pre (state/balance state debtor)]
       (if (and
            (submits? state debtor amount)
            (policies/permits? (:policies state) :available pre (- pre amount)))
         (record-payment state
                         {:debtor debtor :amount amount :status :failed}
                         2)
         state)))
   :valid? (fn [state {[debtor] :args}] (contains? (:accounts state) debtor))})

(defn- payment-ids
  [state]
  (vec (keys (:payments state))))

(defn- returnable
  [state]
  (vec (for [[pmt-id {:keys [status creditor]}] (:payments state)
             :when (and (= :completed status) (nil? creditor))]
         pmt-id)))

(defn- returned
  [state pmt-id amount]
  (let [{:keys [debtor status creditor]} (get-in state [:payments pmt-id])]
    (if (and (= :completed status) (nil? creditor))
      (-> state
          (assoc-in [:payments pmt-id :status] :returned)
          (update-in [:accounts debtor :available] (fnil + 0) amount)
          (bump-legs debtor))
      state)))

(defn- external?
  [state pmt-id]
  (let [payment (get-in state [:payments pmt-id])]
    (and payment (nil? (:creditor payment)))))

(def return-outbound-payment
  {:run? (fn [state] (seq (returnable state)))
   :args (fn [state] (gen/tuple (gen/elements (returnable state))))
   :next-state
   (fn [state {[pmt-id] :args}]
     (returned state pmt-id (get-in state [:payments pmt-id :amount])))
   :valid? (fn [state {[pmt-id] :args}] (external? state pmt-id))})

(def return-outbound-event
  {:run? (fn [state] (seq (returnable state)))
   :args (fn [state]
           (gen/let [pmt-id (gen/elements (returnable state))]
             [pmt-id (get-in state [:payments pmt-id :amount])]))
   :next-state (fn [state {[pmt-id amount] :args}]
                 (returned state pmt-id amount))
   :valid? (fn [state {[pmt-id] :args}] (external? state pmt-id))})

(def settle-outbound-event
  {:run? (fn [state] (seq (payment-ids state)))
   :args (fn [state] (gen/tuple (gen/elements (payment-ids state))))
   :next-state (fn [state _] state)
   :valid? (fn [state {[pmt-id] :args}] (contains? (:payments state) pmt-id))})

(def reject-outbound-payment
  {:run? (fn [state] (seq (payment-ids state)))
   :args (fn [state] (gen/tuple (gen/elements (payment-ids state))))
   :next-state (fn [state _] state)
   :valid? (fn [state {[pmt-id] :args}] (contains? (:payments state) pmt-id))})

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
