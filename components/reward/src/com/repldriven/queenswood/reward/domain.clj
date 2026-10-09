(ns com.repldriven.queenswood.reward.domain
  (:require
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private customer-product-types
  #{:account-product-type-sub-ledger-current
    :account-product-type-sub-ledger-savings
    :account-product-type-sub-ledger-term-deposit})

(defn opening?
  "Whether a cash-account changelog entry is an account becoming
  opened: the second leg of an opening, never a migration or a resume,
  which leave an opened account opened under another kind."
  [{:keys [status-after change-kind]}]
  (and (= :cash-account-status-opened status-after)
       (= :cash-account-change-kind-open change-kind)))

(defn eligible?
  "An account a reward may be paid to: opened, and a customer's rather
  than the bank's own."
  [account]
  (and (= :cash-account-status-opened (:status account))
       (contains? customer-product-types (:product-type account))))

(defn promised
  "The amount a version promises an account opened under it, or nil."
  [version]
  (some (fn [{:keys [kind amount]}]
          (when (= :reward-kind-opening kind) amount))
        (:reward-terms version)))

(defn paid? [reward] (= :account-reward-status-paid (:status reward)))

(defn new-reward
  [account amount]
  {:bank-id (:bank-id account)
   :reward-id (utility/generate-id "rwd")
   :kind :reward-kind-opening
   :account-id (:account-id account)
   :product-id (:product-id account)
   :version-id (:version-id account)
   :amount amount
   :currency (:currency account)
   :created-at (utility/now)})

(defn- changed
  "`reward` as it reaches `status`, recording when: a new one is
  created in it, an existing one is updated into it."
  [reward status at-key]
  (let [now (utility/now)]
    (cond-> (assoc reward :status status at-key now)
            (:status reward)
            (assoc :updated-at now))))

(defn paid
  [reward transaction-id]
  (-> reward
      (dissoc :deferred-reason)
      (changed :account-reward-status-paid :paid-at)
      (assoc :transaction-id transaction-id)))

(defn deferred
  [reward anomaly]
  (-> reward
      (changed :account-reward-status-deferred :deferred-at)
      (assoc :deferred-reason (error/format-anomaly anomaly))))

(defn reward-transaction
  "The posting that pays `reward` to `account` from `house`: a debit on
  the house account and a credit on the customer's, both default and
  posted, each carrying its product type so the control legs onto 3100
  and the deposit control are appended. The key is the account's, so
  reaching the posting twice records it once."
  [house account reward]
  {:idempotency-key (str "reward-" (:account-id account))
   :transaction-type :transaction-type-reward
   :currency (:currency reward)
   :reference "Welcome reward"
   :legs [{:account-id (:account-id house)
           :product-type (:product-type house)
           :balance-type :balance-type-default
           :balance-status :balance-status-posted
           :side :leg-side-debit
           :amount (:amount reward)}
          {:account-id (:account-id account)
           :product-type (:product-type account)
           :balance-type :balance-type-default
           :balance-status :balance-status-posted
           :side :leg-side-credit
           :amount (:amount reward)}]})
