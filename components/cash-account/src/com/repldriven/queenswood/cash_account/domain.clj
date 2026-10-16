(ns com.repldriven.queenswood.cash-account.domain
  (:refer-clojure :exclude [name])
  (:require
    [com.repldriven.queenswood.calc.interface :as calc]
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility :refer [assoc-some]]

    [clojure.string :as str]))

(def ^:private platform
  "The actor of what the cash-account brick changes on its own, as a
  provider's event opens, closes or readdresses an account."
  {:kind :actor-kind-platform :principal-id "cash-account"})

(defn party->account-type
  [party]
  (if (= :party-type-person (:party-type party))
    :account-type-personal
    :account-type-business))

(defn- check-capability
  [action account-type policies]
  (policy/check-capability policies
                           :cash-account
                           {:action action
                            :account-type account-type}))

(defn- check-total-limit
  [aggregates policies]
  (policy/check-limit
   policies
   :cash-account
   {:aggregate :count
    :window :time-window-instant
    :value (inc (get-in aggregates [:cash-account #{:bank-id}]))}))

(defn- check-operation-limit
  [aggregates policies]
  (policy/check-limit
   policies
   :cash-account-operation
   {:aggregate :count
    :window :time-window-daily
    :value (inc (get-in aggregates
                        [:cash-account-operation
                         #{:bank-id :account-id :requested-on}]))}))

(defn- non-zero-balances
  "Balance buckets whose net (credit - debit) isn't zero, across all
  balance-statuses (pending holds included, not just posted)."
  [balances]
  (remove (fn [b] (= (:credit b 0) (:debit b 0))) balances))

(defn- reported-buckets
  [balances]
  (mapv (fn [b]
          (select-keys b
                       [:balance-type :balance-status :currency
                        :credit :debit]))
        balances))

(defn- check-subtotal-limit
  [product-type account-type currency aggregates policies]
  (policy/check-limit
   policies
   :cash-account
   {:aggregate :count
    :window :time-window-instant
    :product-type product-type
    :account-type account-type
    :currency currency
    :value (inc (get-in aggregates
                        [:cash-account
                         #{:bank-id :product-type :account-type :currency}]))}))

(defn- scan->bban
  [{:keys [sort-code account-number]}]
  (str sort-code account-number))

(defn- scheme-name
  [scheme]
  (str/replace (clojure.core/name scheme) #"^payment-address-scheme-" ""))

(defn address-schemes
  [product-version]
  (let [schemes (:allowed-payment-address-schemes product-version)
        unsupported (remove #{:payment-address-scheme-scan} schemes)]
    (cond
     (empty? schemes)
     (error/reject :cash-account/no-payment-schemes
                   "Product has no allowed payment address schemes")

     (seq unsupported)
     (error/reject :cash-account/unsupported-scheme
                   (str "Unsupported payment address scheme: "
                        (clojure.core/name (first unsupported))))

     :else
     (mapv scheme-name schemes))))

(defn- issued->address
  [{:keys [scheme sort-code account-number]}]
  (case scheme
    "scan" {:scheme :payment-address-scheme-scan
            :scan {:sort-code sort-code :account-number account-number}}))

(defn- with-addresses
  [account issued provider-account-id]
  (let [addresses (mapv issued->address issued)
        bban (some (fn [{:keys [scan]}] (when scan (scan->bban scan)))
                   addresses)]
    (assoc-some (assoc account
                       :payment-addresses addresses
                       :provider-account-id provider-account-id)
                :bban
                bban)))

(defn- enum-suffix
  [kw prefix]
  (subs (clojure.core/name kw)
        (inc (count (clojure.core/name prefix)))))

(defn- ensure-currency-allowed
  [currency product-version]
  (when (not= currency (:currency product-version))
    (error/reject :cash-account/invalid-currency
                  {:message "Currency not allowed for this product"
                   :currency currency})))

(defn- ensure-party-active
  [party]
  (let [status (:status party)]
    (when (not= :party-status-active status)
      (error/reject :cash-account/party-status
                    {:message (str "Party is "
                                   (enum-suffix status :party-status))
                     :party-id (:party-id party)
                     :status status}))))

(defn open-account
  [data product-version as-of party aggregates policies]
  (let [{:keys [bank-id party-id product-id currency name]}
        data
        {:keys [version-id]} product-version
        product-type (:product-type product-version)
        account-type (party->account-type party)]
    (let-nom>
      [_ (when (nil? product-version)
           (error/reject :cash-account/product-not-published
                         {:message (str "No product version published for "
                                        product-id
                                        " effective today")
                          :product-id product-id
                          :as-of as-of}))
       _ (ensure-currency-allowed currency product-version)
       _ (ensure-party-active party)
       _ (check-capability :cash-account-action-open account-type policies)
       _ (check-total-limit aggregates policies)
       _ (check-subtotal-limit product-type
                               account-type
                               currency
                               aggregates
                               policies)
       _ (address-schemes product-version)]
      (let [now (utility/now)]
        (assoc-some {:bank-id bank-id
                     :party-id party-id
                     :product-id product-id
                     :version-id version-id
                     :version-from-on as-of
                     :product-type product-type
                     :account-type account-type
                     :currency currency
                     :name name
                     :account-id (utility/generate-id "acc")
                     :status :cash-account-status-opening
                     :payment-addresses []
                     :created-at now
                     :created-by (:actor data)}
                    :idempotency-key
                    (:idempotency-key data))))))

(defn opening-balances
  [account product-version]
  (let [{:keys [account-id product-type]} account
        ;; Fall back to a single default/posted bucket if the product
        ;; declares none.
        bp (or (:balance-products product-version)
               [{:balance-type :balance-type-default
                 :balance-status :balance-status-posted}])]
    (mapv (fn [{:keys [balance-type balance-status]}]
            {:account-id account-id
             :product-type product-type
             :balance-type balance-type
             :balance-status balance-status})
          bp)))

(defn- moved
  [account status]
  (assoc account
         :status status
         :updated-at (utility/now)
         :updated-by platform))

(defn opened-account
  [account]
  (moved account :cash-account-status-opened))

(defn provider-opened-account
  [account {:keys [provider-account-id addresses]}]
  (moved (with-addresses account addresses provider-account-id)
         :cash-account-status-opened))

(defn refused-account
  [account reason]
  (assoc-some (moved account :cash-account-status-refused)
              :refused-reason
              reason))

(def ^:private closeable-statuses
  #{:cash-account-status-opened :cash-account-status-suspended
    :cash-account-status-closing})

(def ^:private emptied-buckets
  "The buckets a close empties itself: the posted default one, which it
  pays out, and the posted interest-accrued one, which it settles."
  #{[:balance-type-default :balance-status-posted]
    [:balance-type-interest-accrued :balance-status-posted]})

(defn- check-buckets
  "Refuses `account`'s close where `balances` hold a bucket the close
  cannot empty itself, a pending hold or another posted bucket, unless
  `policies` waive the check."
  [account balances policies]
  (let [offending (remove (fn [b]
                            (contains? emptied-buckets
                                       [(:balance-type b) (:balance-status b)]))
                          (non-zero-balances balances))]
    (when (and (seq offending)
               (error/anomaly?
                (check-capability :cash-account-action-close-non-zero
                                  (:account-type account)
                                  policies)))
      (error/reject :cash-account/non-zero-on-close
                    {:message "Account has balance buckets a close cannot empty"
                     :account-id (:account-id account)
                     :balances (reported-buckets offending)}))))

(defn- check-payout-account
  "Refuses a `payout` that is not another opened account of `account`'s
  party in its currency. `payout-account-id` names it, and `payout` is
  nil where no such account is found."
  [account payout-account-id payout]
  (when (and payout-account-id
             (not (and payout
                       (= :cash-account-status-opened (:status payout))
                       (= (:party-id account) (:party-id payout))
                       (= (:currency account) (:currency payout))
                       (not= (:account-id account) (:account-id payout)))))
    (error/reject
     :cash-account/invalid-payout-account
     {:message
      "A payout account is another opened account of the same party and currency"
      :account-id (:account-id account)
      :payout-account-id payout-account-id})))

(defn close-account
  "`account` as a close by `actor` leaves it, `closing`, where its status,
  `policies` and `balances` allow one, and the payout account
  `payout-account-id` names, `payout`, qualifies."
  [account balances payout-account-id payout actor policies]
  (let-nom>
    [_ (when-not (contains? closeable-statuses (:status account))
         (error/reject :cash-account/invalid-status
                       {:message "Account is not in a closeable state"
                        :account-id (:account-id account)
                        :status (:status account)
                        :allowed closeable-statuses}))
     _ (check-capability :cash-account-action-close
                         (:account-type account)
                         policies)
     _ (check-payout-account account payout-account-id payout)
     _ (check-buckets account balances policies)]
    (assoc account
           :status :cash-account-status-closing
           :updated-at (utility/now)
           :updated-by actor)))

(def ^:private period-units
  {:interest-period-unit-day :day
   :interest-period-unit-week :week
   :interest-period-unit-month :month})

(defn- window-end
  "The epoch day early closure `window` closes on, counted from the day
  the account came onto its version, `version-from-on`."
  [window version-from-on]
  (let [{:keys [unit count]} window]
    (calc/plus-units version-from-on (period-units unit) count)))

(defn closure-term
  "What `account`'s closure on `day` is held to by its version's
  `closure-terms`: the `charged` term, `{:interest :fee}`, of the first
  early window still open on that day, or after the last the terms' own
  `{:interest}`. A `refused` window is refused
  `:cash-account/early-closure-refused`, naming the day it closes on."
  [account closure-terms day]
  (let [{:keys [interest early]} closure-terms
        from-on (:version-from-on account)
        window (first (filter (fn [window] (< day (window-end window from-on)))
                              early))
        {:keys [refused charged]} (:kind window)]
    (cond refused
          (error/reject :cash-account/early-closure-refused
                        {:message
                         "The account's version refuses a closure this early"
                         :account-id (:account-id account)
                         :closable-from (utility/epoch-day->iso-date
                                         (window-end window from-on))})

          charged
          charged

          :else
          {:interest interest})))

(def ^:private basis-points 10000)

(defn closure-fee
  "What `fee` charges a closure leaving `held` in the default bucket: a
  `flat` amount, or `balance`'s `rate-bps` of `held`, rounded down, raised
  to its `min` and capped at its `max`. Never more than `held`, and zero
  where there is no fee or nothing is held."
  [fee held]
  (let [{:keys [flat balance]} (:kind fee)
        charged (cond flat
                      (:amount flat)

                      balance
                      (let [{lowest :min highest :max} balance]
                        (cond-> (quot (* held (:rate-bps balance))
                                      basis-points)
                                lowest
                                (max lowest)

                                highest
                                (min highest)))

                      :else
                      0)]
    (max 0 (min charged held))))

(defn check-emptied
  "Refuses a close leaving `held` in `account`'s default bucket, once its
  interest is settled and its fee charged, where it names no payout
  account to move it to, unless `policies` waive the check, and one
  leaving it overdrawn."
  [account held payout policies]
  (when (or (neg? held)
            (and (pos? held)
                 (nil? payout)
                 (error/anomaly?
                  (check-capability :cash-account-action-close-non-zero
                                    (:account-type account)
                                    policies))))
    (error/reject
     :cash-account/non-zero-on-close
     {:message
      "Account holds a balance; name a payout account to move it to"
      :account-id (:account-id account)
      :balances [{:balance-type :balance-type-default
                  :balance-status :balance-status-posted
                  :currency (:currency account)
                  :credit (max held 0)
                  :debit (max (- held) 0)}]})))

(defn default-held
  "What `balances` hold in the posted default bucket, credits less
  debits."
  [balances]
  (transduce (comp (filter (fn [b]
                             (and (= :balance-type-default (:balance-type b))
                                  (= :balance-status-posted
                                     (:balance-status b)))))
                   (map (fn [b] (- (:credit b 0) (:debit b 0)))))
             +
             0
             balances))

(defn fee-transaction
  "The `fee` transaction charging closing `account` `amount` from its
  default bucket, credited to the fee income account `fee-income-id`."
  [account fee-income-id amount business-day]
  (let [{:keys [bank-id account-id product-type currency]} account]
    {:bank-id bank-id
     :idempotency-key (str "closure-fee-" account-id)
     :transaction-type :transaction-type-fee
     :currency currency
     :reference (str "Closure fee " (utility/epoch-day->iso-date business-day))
     :legs [{:account-id account-id
             :product-type product-type
             :balance-type :balance-type-default
             :balance-status :balance-status-posted
             :side :leg-side-debit
             :amount amount
             :currency currency}
            {:account-id fee-income-id
             :product-type :account-product-type-general-ledger
             :balance-type :balance-type-default
             :balance-status :balance-status-posted
             :side :leg-side-credit
             :amount amount
             :currency currency}]}))

(def held-on-close
  "Why a close the provider carried out leaves the account closing."
  "The account held a balance when its provider closed it")

(defn emptied?
  "Whether `balances` are all zero, so the account they belong to may be
  closed."
  [balances]
  (empty? (non-zero-balances balances)))

(defn closed-account
  [account]
  (moved account :cash-account-status-closed))

(defn close-refused-account
  "The account as a refused close leaves it: back in the status it was
  closed from, which its close operation holds."
  [account operation]
  (moved account (:account-status operation)))

(defn suspend-account
  [account actor policies]
  (let-nom>
    [_ (when-not (= :cash-account-status-opened (:status account))
         (error/reject :cash-account/invalid-status
                       {:message "Account is not in a suspendable state"
                        :account-id (:account-id account)
                        :status (:status account)
                        :allowed #{:cash-account-status-opened}}))
     _ (check-capability :cash-account-action-suspend
                         (:account-type account)
                         policies)]
    (let [now (utility/now)]
      (assoc account
             :status :cash-account-status-suspended
             :updated-by actor
             :updated-at now))))

(defn resume-account
  [account actor aggregates policies]
  (let-nom>
    [_ (when-not (= :cash-account-status-suspended (:status account))
         (error/reject :cash-account/invalid-status
                       {:message "Account is not in a resumable state"
                        :account-id (:account-id account)
                        :status (:status account)
                        :allowed #{:cash-account-status-suspended}}))
     _ (check-capability :cash-account-action-resume
                         (:account-type account)
                         policies)
     _ (check-operation-limit aggregates policies)]
    (let [now (utility/now)]
      (assoc account
             :status :cash-account-status-opened
             :updated-by actor
             :updated-at now))))

(def ^:private operation-pending :cash-account-operation-status-pending)

(defn new-operation
  "An operation of `kind` asked of `account`, as it was before the
  operation, by the command in `data`: pending until its provider
  answers, or completed where the account has no account at its provider
  to carry it out."
  [account kind today data]
  (let [{:keys [actor idempotency-key payout-account-id]} data
        now (utility/now)]
    (assoc-some {:bank-id (:bank-id account)
                 :account-id (:account-id account)
                 :operation-id (utility/generate-id "cao")
                 :kind kind
                 :status (if (:provider-account-id account)
                           operation-pending
                           :cash-account-operation-status-completed)
                 :requested-on today
                 :account-status (:status account)
                 :created-at now
                 :created-by actor}
                :idempotency-key
                idempotency-key
                :payout-account-id
                payout-account-id)))

(defn pending-operation?
  [operation]
  (= operation-pending (:status operation)))

(defn completed-operation
  "The operation as its provider's carrying it out leaves it."
  [operation]
  (assoc operation
         :status :cash-account-operation-status-completed
         :updated-at (utility/now)
         :updated-by platform))

(defn refused-operation
  "The operation as its provider's refusal leaves it, with the reason it
  gave."
  [operation reason]
  (utility/assoc-some (assoc operation
                             :status :cash-account-operation-status-refused
                             :updated-at (utility/now)
                             :updated-by platform)
                      :refused-reason
                      reason))

(defn request-rotation
  "The account, unchanged, where it may be given new payment addresses:
  an opened account, within its operations for the day."
  [account aggregates policies]
  (let-nom>
    [_ (when-not (= :cash-account-status-opened (:status account))
         (error/reject :cash-account/invalid-status
                       {:message "Account is not in a rotatable state"
                        :account-id (:account-id account)
                        :status (:status account)
                        :allowed #{:cash-account-status-opened}}))
     _ (check-capability :cash-account-action-rotate-address
                         (:account-type account)
                         policies)
     _ (check-operation-limit aggregates policies)]
    account))

(defn reissued-account
  [account {:keys [provider-account-id addresses]}]
  (let [now (utility/now)
        retired (mapv (fn [address] {:address address :retired-at now})
                      (:payment-addresses account))]
    (-> account
        (dissoc :bban)
        (with-addresses addresses provider-account-id)
        (assoc :retired-payment-addresses
               (into (vec (:retired-payment-addresses account)) retired)
               :updated-at now
               :updated-by platform))))

(defn migrate-product
  "Repin an opened account to another product version. Direct
  single-phase flip, no second leg.

  Only the pin moves, with `:version-from-on` set to the day `as-of`. Balances,
  payment addresses and the account number are untouched, because the
  account is the same account on different terms — a customer whose
  savings rate changed did not get a new account. The product may change
  too, so `:product-id` follows the target rather than being asserted
  equal.

  What the target is allowed to be — published, the same product type,
  a currency this account may hold — belongs to whoever assembled the
  cohort, not here. This guards the account's own state and nothing
  else, so a single account can be moved on its own without
  reconstructing a migration's reasoning. `actor` moves it, the
  platform when a migration runs."
  [account target-version as-of policies actor]
  (let-nom>
    [_ (when-not (= :cash-account-status-opened (:status account))
         (error/reject :cash-account/invalid-status
                       {:message "Account is not in a migratable state"
                        :account-id (:account-id account)
                        :status (:status account)
                        :allowed #{:cash-account-status-opened}}))
     _ (check-capability :cash-account-action-migrate
                         (:account-type account)
                         policies)]
    (-> account
        (assoc :product-id (:product-id target-version)
               :version-id (:version-id target-version)
               :version-from-on as-of
               :updated-at (utility/now)
               :updated-by actor))))
