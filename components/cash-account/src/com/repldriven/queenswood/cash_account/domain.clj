(ns com.repldriven.queenswood.cash-account.domain
  (:refer-clojure :exclude [name])
  (:require
    [com.repldriven.queenswood.balance-domain.interface :as balance-domain]
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility :refer [assoc-some]]))

(defn party->account-type
  [party]
  (if (= :party-type-person (:type party))
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

(defn- new-addresses
  [product-version address-fountain-fn sort-code]
  (let [schemes (:allowed-payment-address-schemes product-version)]
    (cond
     (empty? schemes)
     (error/reject :cash-account/no-payment-schemes
                   "Product has no allowed payment address schemes")

     :else
     (reduce (fn [addresses scheme]
               (case scheme
                 :payment-address-scheme-scan
                 (let [account-number (address-fountain-fn sort-code)]
                   (conj addresses
                         {:scheme :payment-address-scheme-scan
                          :scan {:sort-code sort-code
                                 :account-number account-number}}))

                 (reduced (error/reject :cash-account/unsupported-scheme
                                        (str
                                         "Unsupported payment address scheme: "
                                         (clojure.core/name scheme))))))
             []
             schemes))))

(defn- enum-suffix
  [kw prefix]
  (subs (clojure.core/name kw)
        (inc (count (clojure.core/name prefix)))))

(defn- ensure-currency-allowed
  [currency product-version]
  (let [allowed (:allowed-currencies product-version)]
    (when (and (seq allowed)
               (not (some #{currency} allowed)))
      (error/reject :cash-account/invalid-currency
                    {:message "Currency not allowed for this product"
                     :currency currency}))))

(defn- ensure-party-active
  [party]
  (let [status (:status party)]
    (when (not= :party-status-active status)
      (error/reject :cash-account/party-status
                    {:message (str "Party is "
                                   (enum-suffix status :party-status))
                     :party-id (:party-id party)
                     :status status}))))

(defn ensure-product-exists
  "Reject an unknown product id. `get-product` answers for any id with
  an aggregate rather than nil or a rejection, so an empty `:versions`
  is the only evidence that no such product exists."
  [product]
  (when (empty? (:versions product))
    (error/reject :cash-account/product-not-found
                  {:message "Product not found"
                   :product-id (:product-id product)})))

(defn open-account
  "Build a cash-account record from input data and a published product
  version: derives account-type from the holder party, runs the open
  capability + count limits, and allocates payment-addresses."
  [data product-version as-of party address-fountain-fn aggregates policies]
  (let [{:keys [bank-id party-id product-id currency name sort-code]}
        data
        {:keys [version-id]} product-version
        product-type (:product-type product-version)
        account-type (party->account-type party)]
    (let-nom>
      [_ (when (nil? product-version)
           (error/reject :cash-account/product-not-published
                         {:message (str "No product version published for "
                                        product-id
                                        " effective on epoch day "
                                        as-of)
                          :product-id product-id
                          :as-of as-of}))
       _ (when (nil? sort-code)
           (error/reject :cash-account/missing-sort-code
                         {:message "No sort code supplied for account opening"
                          :bank-id bank-id}))
       _ (ensure-currency-allowed currency product-version)
       _ (ensure-party-active party)
       _ (check-capability :cash-account-action-open account-type policies)
       _ (check-total-limit aggregates policies)
       _ (check-subtotal-limit product-type
                               account-type
                               currency
                               aggregates
                               policies)
       payment-addresses (new-addresses product-version
                                        address-fountain-fn
                                        sort-code)]
      (let [now (utility/now)
            bban (some (fn [{:keys [scan]}] (when scan (scan->bban scan)))
                       payment-addresses)]
        (assoc-some {:bank-id bank-id
                     :party-id party-id
                     :product-id product-id
                     :version-id version-id
                     :product-type product-type
                     :account-type account-type
                     :currency currency
                     :name name
                     :account-id (utility/generate-id "acc")
                     :account-status :cash-account-status-opening
                     :payment-addresses payment-addresses
                     :created-at now
                     :updated-at now}
                    :bban
                    bban
                    :idempotency-key
                    (:idempotency-key data))))))

(defn opening-balances
  [account currency product-version]
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
             :balance-status balance-status
             :currency currency})
          bp)))

(defn opened-account
  [account]
  (assoc account
         :account-status :cash-account-status-opened
         :updated-at (utility/now)))

(defn close-account
  [account balances policies]
  (let-nom>
    [_ (when-not (= :cash-account-status-opened (:account-status account))
         (error/reject :cash-account/invalid-status
                       {:message "Account is not in a closeable state"
                        :account-id (:account-id account)
                        :status (:account-status account)
                        :allowed #{:cash-account-status-opened}}))
     _ (check-capability :cash-account-action-close
                         (:account-type account)
                         policies)
     _ (when (and (seq (non-zero-balances balances))
                  (error/anomaly?
                   (check-capability :cash-account-action-close-non-zero
                                     (:account-type account)
                                     policies)))
         (error/reject :cash-account/non-zero-on-close
                       {:message "Account has a non-zero balance"
                        :account-id (:account-id account)
                        :posted-balance (balance-domain/posted-balance
                                         balances
                                         (:currency account))}))]
    (assoc account
           :account-status :cash-account-status-closing
           :updated-at (utility/now))))

(defn closed-account
  [account]
  (assoc account
         :account-status :cash-account-status-closed
         :updated-at (utility/now)))

(defn suspend-account
  [account policies]
  (let-nom>
    [_ (when-not (= :cash-account-status-opened (:account-status account))
         (error/reject :cash-account/invalid-status
                       {:message "Account is not in a suspendable state"
                        :account-id (:account-id account)
                        :status (:account-status account)
                        :allowed #{:cash-account-status-opened}}))
     _ (check-capability :cash-account-action-suspend
                         (:account-type account)
                         policies)]
    (assoc account
           :account-status :cash-account-status-suspended
           :updated-at (utility/now))))

(defn resume-account
  [account policies]
  (let-nom>
    [_ (when-not (= :cash-account-status-suspended (:account-status account))
         (error/reject :cash-account/invalid-status
                       {:message "Account is not in a resumable state"
                        :account-id (:account-id account)
                        :status (:account-status account)
                        :allowed #{:cash-account-status-suspended}}))
     _ (check-capability :cash-account-action-resume
                         (:account-type account)
                         policies)]
    (assoc account
           :account-status :cash-account-status-opened
           :updated-at (utility/now))))

(defn rotate-address
  "Replace an opened account's payment addresses with a freshly
  allocated set drawn from the product version's allowed schemes,
  retiring the old ones on-record (QNS-20). The old addresses are
  never redirected — a payment landing on a retired address is a
  lookup miss for `get-account-by-bban`, which already falls into
  the suspense path.

  The rotation stamps its own idempotency key onto the account, so a
  retry under that key can be told from a fresh rotation."
  [account data product-version address-fountain-fn policies]
  (let-nom>
    [_ (when-not (= :cash-account-status-opened (:account-status account))
         (error/reject :cash-account/invalid-status
                       {:message "Account is not in a rotatable state"
                        :account-id (:account-id account)
                        :status (:account-status account)
                        :allowed #{:cash-account-status-opened}}))
     _ (check-capability :cash-account-action-rotate-address
                         (:account-type account)
                         policies)
     sort-code (some (fn [{:keys [scan]}] (when scan (:sort-code scan)))
                     (:payment-addresses account))
     new-payment-addresses (new-addresses product-version
                                          address-fountain-fn
                                          sort-code)]
    (let [now (utility/now)
          bban (some (fn [{:keys [scan]}] (when scan (scan->bban scan)))
                     new-payment-addresses)
          retired (mapv (fn [address] {:address address :retired-at now})
                        (:payment-addresses account))]
      (assoc-some (assoc account
                         :payment-addresses new-payment-addresses
                         :retired-payment-addresses
                         (into (vec (:retired-payment-addresses account))
                               retired)
                         :updated-at now)
                  :bban
                  bban
                  :last-rotation-idempotency-key
                  (:idempotency-key data)))))

(defn migrate-product
  "Repin an opened account to another product version. Direct
  single-phase flip, no second leg.

  Only the pin moves. Balances, payment addresses and the account
  number are untouched, because the account is the same account on
  different terms — a customer whose savings rate changed did not get a
  new account. The product may change too, so `:product-id` follows the
  target rather than being asserted equal.

  What the target is allowed to be — published, the same product type,
  a currency this account may hold — belongs to whoever assembled the
  cohort, not here. This guards the account's own state and nothing
  else, so a single account can be moved on its own without
  reconstructing a migration's reasoning."
  [account target-version policies]
  (let-nom>
    [_ (when-not (= :cash-account-status-opened (:account-status account))
         (error/reject :cash-account/invalid-status
                       {:message "Account is not in a migratable state"
                        :account-id (:account-id account)
                        :status (:account-status account)
                        :allowed #{:cash-account-status-opened}}))
     _ (check-capability :cash-account-action-migrate
                         (:account-type account)
                         policies)]
    (assoc account
           :product-id (:product-id target-version)
           :version-id (:version-id target-version)
           :updated-at (utility/now))))
