(ns com.repldriven.queenswood.cash-account.domain
  (:refer-clojure :exclude [name])
  (:require
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility :refer [assoc-some]]

    [clojure.string :as str]))

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
                     :created-by (:actor data)
                     :updated-at now}
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

(defn opened-account
  [account]
  (let [now (utility/now)]
    (assoc account
           :status :cash-account-status-opened
           :opened-at now
           :updated-at now)))

(defn provider-opened-account
  [account {:keys [provider-account-id addresses]}]
  (let [now (utility/now)]
    (assoc (with-addresses account addresses provider-account-id)
           :status :cash-account-status-opened
           :opened-at now
           :updated-at now)))

(defn refused-account
  [account reason]
  (assoc-some (assoc account
                     :status :cash-account-status-refused
                     :updated-at (utility/now))
              :failure-reason
              reason))

(defn close-account
  [account balances actor policies]
  (let-nom>
    [_ (when-not (contains? #{:cash-account-status-opened
                              :cash-account-status-suspended}
                            (:status account))
         (error/reject :cash-account/invalid-status
                       {:message "Account is not in a closeable state"
                        :account-id (:account-id account)
                        :status (:status account)
                        :allowed #{:cash-account-status-opened
                                   :cash-account-status-suspended}}))
     _ (check-capability :cash-account-action-close
                         (:account-type account)
                         policies)
     offending (non-zero-balances balances)
     _ (when (and (seq offending)
                  (error/anomaly?
                   (check-capability :cash-account-action-close-non-zero
                                     (:account-type account)
                                     policies)))
         (error/reject :cash-account/non-zero-on-close
                       {:message "Account has non-zero balance buckets"
                        :account-id (:account-id account)
                        :balances (reported-buckets offending)}))]
    (let [now (utility/now)]
      (assoc account
             :status :cash-account-status-closing
             :closed-at now
             :closed-by actor
             :updated-at now))))

(defn closed-account
  [account]
  (assoc account
         :status :cash-account-status-closed
         :updated-at (utility/now)))

(defn- suspended-when-closed?
  [account]
  (let [{:keys [suspended-at resumed-at]} account]
    (boolean (and suspended-at (> suspended-at (or resumed-at 0))))))

(defn close-refused-account
  [account]
  (-> account
      (dissoc :closed-at :closed-by)
      (assoc :status (if (suspended-when-closed? account)
                       :cash-account-status-suspended
                       :cash-account-status-opened)
             :updated-at (utility/now))))

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
             :suspended-at now
             :suspended-by actor
             :updated-at now))))

(defn resume-account
  [account actor policies]
  (let-nom>
    [_ (when-not (= :cash-account-status-suspended (:status account))
         (error/reject :cash-account/invalid-status
                       {:message "Account is not in a resumable state"
                        :account-id (:account-id account)
                        :status (:status account)
                        :allowed #{:cash-account-status-suspended}}))
     _ (check-capability :cash-account-action-resume
                         (:account-type account)
                         policies)]
    (let [now (utility/now)]
      (assoc account
             :status :cash-account-status-opened
             :resumed-at now
             :resumed-by actor
             :updated-at now))))

(defn request-rotation
  [account data policies]
  (let-nom>
    [_ (when-not (= :cash-account-status-opened (:status account))
         (error/reject :cash-account/invalid-status
                       {:message "Account is not in a rotatable state"
                        :account-id (:account-id account)
                        :status (:status account)
                        :allowed #{:cash-account-status-opened}}))
     _ (check-capability :cash-account-action-rotate-address
                         (:account-type account)
                         policies)]
    (let [rotation-key (or (:idempotency-key data)
                           (str (utility/uuidv7)))
          now (utility/now)]
      (assoc account
             :rotation {:idempotency-key rotation-key
                        :status :address-rotation-status-pending}
             :rotated-at now
             :rotated-by (:actor data)
             :updated-at now))))

(defn reissue-failed-account
  [account reason]
  (-> account
      (update :rotation
              assoc-some
              :status :address-rotation-status-failed
              :failure-reason reason)
      (assoc :updated-at (utility/now))))

(defn reissued-account
  [account {:keys [provider-account-id addresses]}]
  (let [now (utility/now)
        retired (mapv (fn [address] {:address address :retired-at now})
                      (:payment-addresses account))]
    (-> account
        (assoc-in [:rotation :status] :address-rotation-status-completed)
        (dissoc :bban)
        (with-addresses addresses provider-account-id)
        (assoc :retired-payment-addresses
               (into (vec (:retired-payment-addresses account)) retired)
               :updated-at now))))

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
  reconstructing a migration's reasoning."
  [account target-version as-of policies]
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
    (assoc account
           :product-id (:product-id target-version)
           :version-id (:version-id target-version)
           :version-from-on as-of
           :updated-at (utility/now))))
