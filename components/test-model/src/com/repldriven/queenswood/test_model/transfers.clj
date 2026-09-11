(ns com.repldriven.queenswood.test-model.transfers
  (:require
    [com.repldriven.queenswood.test-model.policy :as policy]
    [com.repldriven.queenswood.test-model.state :as state]

    [clojure.test.check.generators :as gen]))

(defn- operable?
  "True when money may move on `acct` — reality's payment brick moves
  it only on an opened account, and the model's only non-open status
  is `:closed`."
  [state acct]
  (= :open (get-in state [:accounts acct :status])))

(defn- bump-legs
  [state & accts]
  (reduce (fn [s a] (update-in s [:accounts a :transaction-legs] (fnil inc 0)))
          state
          accts))

(defn- apply-delta
  [state acct delta]
  (let [pre (state/balance state acct)
        post (+ pre delta)]
    (if (policy/permits? (:policies state) :available pre post)
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
    (if (and (policy/permits? (:policies state) :available pre-from post-from)
             (policy/permits? (:policies state) :available pre-to post-to))
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
   :next-state (fn [state {[acct amount] :args}]
                 ;; A credit to a non-operable account parks in the
                 ;; bank's suspense instead of landing: the receipt is
                 ;; still recorded, the account's balance is not.
                 (let [marker (state/next-inbound-id state)
                       advanced (if (operable? state acct)
                                  (apply-delta state acct amount)
                                  state)]
                   (-> advanced
                       (update :inbound-payments conj marker)
                       (update :next-inbound-id inc))))
   :valid? (fn [state {[acct] :args}] (contains? (:accounts state) acct))})

(def outbound-transfer
  {:run? (fn [state] (seq (state/known-accounts state)))
   :args (fn [state]
           (gen/tuple (gen/elements (state/known-accounts state))
                      (gen/choose 1 10000)))
   :next-state (fn [state {[acct amount] :args}]
                 (apply-delta state acct (- amount)))
   :valid? (fn [state {[acct] :args}] (contains? (:accounts state) acct))})

(def outbound-payment
  "Two-arg `[debtor amount]` pays an external creditor — debits the
  debtor and records the payment. Three-arg
  `[debtor creditor amount]` pays a known model account; the
  bank-payment event-processor recognises the creditor BBAN as
  internal on the schemes-payments-event settled callback and
  credits it. The verb publishes the schemes-payment-command on
  the bus; ClearBank settles it asynchronously and the
  event-processor flips the OutboundPayment to `:completed`. The
  model mirrors that auto-settle here by marking `:status
  :completed` straight away (so by the time the next model-eq
  check fires — possibly after an explicit `:wait` — the model
  matches reality without depending on a hand-driven
  `:settle-outbound-payment`)."
  {:run? (fn [state] (seq (state/known-accounts state)))
   :args (fn [state]
           (gen/tuple (gen/elements (state/known-accounts state))
                      (gen/choose 1 10000)))
   :next-state
   (fn [state {args :args}]
     (let [[debtor creditor amount] (case (count args)
                                      2 [(first args) nil (second args)]
                                      3 args)]
       ;; Reality rejects non-positive amounts via
       ;; `:transaction/invalid-amount` — predict no-op.
       (if-not (pos? amount)
         state
         (let [advanced (cond
                         ;; Reality refuses the submission outright.
                         (not (operable? state debtor))
                         state

                         ;; The debit leaves either way; a non-operable
                         ;; creditor's credit parks in suspense.
                         (and creditor (operable? state creditor))
                         (transfer-between state debtor creditor amount)

                         :else
                         (apply-delta state debtor (- amount)))]
           (if (= advanced state)
             state
             (let [pmt-id (state/next-payment-id advanced)]
               (-> advanced
                   ;; Reservation model: the debtor carries three legs by
                   ;; settlement — the pending-outgoing reservation at
                   ;; submit, then its clearing credit plus the posted
                   ;; debit at settle. `apply-delta` / `transfer-between`
                   ;; counted the first; add the two settlement legs.
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

(def settle-outbound-payment
  "Idempotent on the model side: `:outbound-payment` already marked
  the payment `:completed`, so re-marking it here is a no-op.
  Reality's settle-outbound is similarly idempotent
  (`Outbound payment settlement already completed`), so
  hand-authored scenarios that drive a redelivery to exercise the
  idempotency contract still match between model and reality."
  {:run? (fn [state] (seq (:payments state)))
   :args (fn [state] (gen/tuple (gen/elements (keys (:payments state)))))
   :next-state (fn [state {[pmt-id] :args}]
                 (assoc-in state [:payments pmt-id :status] :completed))
   :valid? (fn [state {[pmt-id] :args}] (contains? (:payments state) pmt-id))})

(defn- accounts-by-org
  "Returns a map of bank-id → vector of account-ids for known
  accounts. Used to constrain `internal-transfer` to same-org
  pairs (the production API enforces same-org)."
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
   :next-state (fn [state {[from to amount currency] :args}]
                 ;; Generator is constrained to positive amounts,
                 ;; same-org pairs, and distinct accounts. Explicit
                 ;; scenarios may still pass cross-org / self / zero
                 ;; / negative / currency-mismatch cases that reality
                 ;; rejects (see the *-rejected.edn fixtures). The
                 ;; model predicts a no-op for any rejection-bound
                 ;; input. Scenario accounts are implicitly GBP
                 ;; (the only currency the verbs allocate), so a
                 ;; non-GBP explicit currency is a mismatch.
                 (if (and (pos? amount)
                          (not= from to)
                          (operable? state from)
                          (operable? state to)
                          (= (get-in state [:accounts from :bank])
                             (get-in state [:accounts to :bank]))
                          (or (nil? currency) (= "GBP" currency)))
                   (transfer-between state from to amount)
                   state))
   :valid? (fn [state {[from to] :args}]
             (and (contains? (:accounts state) from)
                  (contains? (:accounts state) to)))})
