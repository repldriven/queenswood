(ns com.repldriven.queenswood.reward.domain
  (:require
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private customer-product-types
  #{:product-type-sub-ledger-current :product-type-sub-ledger-savings
    :product-type-sub-ledger-term-deposit})

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

(defn paid? [reward] (= :reward-status-paid (:status reward)))

(defn new-reward
  [account amount]
  (let [now (utility/now)]
    {:bank-id (:bank-id account)
     :reward-id (utility/generate-id "rwd")
     :account-id (:account-id account)
     :party-id (:party-id account)
     :product-id (:product-id account)
     :version-id (:version-id account)
     :kind :reward-kind-opening
     :amount amount
     :currency (:currency account)
     :status :reward-status-due
     :created-at now
     :updated-at now}))

(defn paid
  [reward transaction-id]
  (let [now (utility/now)]
    (-> reward
        (dissoc :error)
        (assoc :status :reward-status-paid
               :transaction-id transaction-id
               :paid-at now
               :updated-at now))))

(defn deferred
  [reward anomaly]
  (assoc reward
         :status :reward-status-due
         :error (error/format-anomaly anomaly)
         :updated-at (utility/now)))

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
