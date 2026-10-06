(ns com.repldriven.queenswood.payment.domain.provider-transfer
  (:require
    [com.repldriven.mono.utility.interface :as utility]))

(defn- mirrored?
  "True for a leg that moves money a provider account holds: a posting
  to the spendable balance."
  [{:keys [balance-type balance-status]}]
  (and (= :balance-type-default balance-type)
       (= :balance-status-posted balance-status)))

(defn- net
  [{:keys [side amount]}]
  (if (= :leg-side-credit side) amount (- amount)))

(defn- pair
  "Match each party the posting took money from with the parties it gave
  money to, in a stable order, until both sides are spent."
  [debtors creditors]
  (loop [ds debtors
         cs creditors
         out []]
    (if (or (empty? ds) (empty? cs))
      out
      (let [[d owed] (first ds)
            [c due] (first cs)
            amount (min owed due)]
        (recur
         (if (= amount owed) (rest ds) (cons [d (- owed amount)] (rest ds)))
         (if (= amount due) (rest cs) (cons [c (- due amount)] (rest cs)))
         (conj out {:debtor d :creditor c :amount amount}))))))

(defn mirrors-nothing?
  "True for a posting `provider-transfers` turns into no transfer whatever
  its accounts' parties: one with no mirrored leg, or one whose mirrored
  legs are all on the account the scheme moved the money through and on
  1100, summing to zero, since 1100 nets to that same account's party."
  [posted cash-at-correspondent-id]
  (let [{:keys [legs scheme-account-id]} posted
        mirrored (filter mirrored? legs)]
    (or (empty? mirrored)
        (and (some? scheme-account-id)
             (every? (fn [{:keys [account-id]}]
                       (contains? #{scheme-account-id cash-at-correspondent-id}
                                  account-id))
                     mirrored)
             (zero? (reduce + 0 (map net mirrored)))))))

(defn provider-transfers
  "The movements between provider accounts that make them hold what the
  posting left in the ledger, each named by the cash accounts whose
  provider accounts hold the money. Nets the posting's mirrored legs per
  party: a cash account, or the bank's own funds for one the provider
  holds nothing for; the scheme's side of 1100 cash at correspondent,
  which is the account the scheme moved the money through, or money from
  outside where the scheme did not; and the bank's own funds for any
  other ledger account and whatever the mirrored legs leave unbalanced.
  Money from outside is a transfer with no debtor, and money leaving to
  it without the scheme stays with the bank's own funds."
  [posted
   {:keys [cash-accounts cash-at-correspondent-id scheme-account-id
           own-funds]}]
  (let [party (fn [{:keys [account-id]}]
                (cond
                 (contains? cash-accounts account-id)
                 (get cash-accounts account-id)

                 (= cash-at-correspondent-id account-id)
                 (or scheme-account-id ::outside)

                 :else
                 own-funds))
        nets (reduce (fn [m leg] (update m (party leg) (fnil + 0) (net leg)))
                     {}
                     (filter mirrored? (:legs posted)))
        outside (get nets ::outside 0)
        nets (cond-> nets
                     (pos? outside)
                     (-> (dissoc ::outside)
                         (update own-funds (fnil + 0) outside)))
        residual (- (reduce + 0 (vals nets)))
        nets (cond-> nets
                     (not (zero? residual))
                     (update own-funds (fnil + 0) residual))
        ordered (sort-by (comp str key) nets)]
    (mapv (fn [{:keys [debtor] :as t}]
            (assoc t :debtor (when-not (= ::outside debtor) debtor)))
          (pair (keep (fn [[k v]] (when (neg? v) [k (- v)])) ordered)
                (keep (fn [[k v]] (when (pos? v) [k v])) ordered)))))

(defn mirror-party
  "Whose provider account holds `account`'s money: its own where the
  provider holds one for it, or will once it opens it, and otherwise the
  bank's own funds."
  [account own-funds]
  (let [{:keys [account-id provider-account-id account-status]} account]
    (if (or provider-account-id
            (= account-id own-funds)
            (= :cash-account-status-opening account-status))
      account-id
      own-funds)))

(defn new-provider-transfer
  [posted {:keys [debtor creditor amount]}]
  (let [{:keys [bank-id transaction-id currency]} posted
        now (utility/now)]
    (utility/assoc-some {:transfer-id (utility/generate-id "ptr")
                         :bank-id bank-id
                         :transaction-id transaction-id
                         :creditor-account-id creditor
                         :amount amount
                         :currency currency
                         :status :provider-transfer-status-pending
                         :created-at now
                         :updated-at now}
                        :debtor-account-id
                        debtor)))

(defn transfer-outcome
  "The transfer as its outcome leaves it, or nil where it is no longer
  pending."
  [transfer status reason]
  (when (= :provider-transfer-status-pending (:status transfer))
    (utility/assoc-some
     (assoc transfer :status status :updated-at (utility/now))
     :failure-reason
     reason)))
