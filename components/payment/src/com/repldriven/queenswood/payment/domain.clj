(ns com.repldriven.queenswood.payment.domain
  (:require
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility])
  (:import
    (java.time Instant ZoneId)))

(defn current-business-day
  "Returns the epoch-day (long) of the business day that contains
  `now-ms` under `cutoff`. A timestamp that falls in a
  zone-local time-of-day before `:hour-of-day` counts as the
  *previous* day's bucket. A missing `cutoff` (nil or empty map)
  defaults to UTC midnight — i.e. plain calendar day in UTC."
  [^long now-ms cutoff]
  (let [{:keys [zone hour-of-day]
         :or {zone "UTC" hour-of-day 0}}
        cutoff]
    (-> (Instant/ofEpochMilli now-ms)
        (.atZone (ZoneId/of zone))
        (.minusHours (long hour-of-day))
        .toLocalDate
        .toEpochDay)))

(def ^:private operable-statuses #{:cash-account-status-opened})

(def ^:private role->not-operable
  {:debtor :payment/debtor-account-not-operable
   :creditor :payment/creditor-account-not-operable})

(defn operable?
  "True when money may move on `account` — it is opened. The status
  vocabulary the payment brick reads lives here alone."
  [account]
  (contains? operable-statuses (:account-status account)))

(defn ensure-account-operable
  "Rejects when `account` is not operable. `role` is `:debtor` or
  `:creditor` and selects the rejection kind."
  [account role]
  (when-not (operable? account)
    (error/reject (role->not-operable role)
                  {:message (str "The "
                                 (name role)
                                 " account is not open for payments")
                   :account-id (:account-id account)
                   :status (:account-status account)
                   :allowed operable-statuses})))

(defn- ensure-distinct-accounts
  [debtor-account-id creditor-account-id]
  (when (= debtor-account-id creditor-account-id)
    (error/reject :payment/self-transfer-not-permitted
                  {:message "Debtor and creditor accounts must differ"
                   :account-id debtor-account-id})))

(defn- ensure-currency-matches
  [payment-currency account]
  (when (not= payment-currency (:currency account))
    (error/reject :payment/currency-mismatch
                  {:message
                   "Payment currency must match account currency"
                   :payment-currency payment-currency
                   :account-id (:account-id account)
                   :account-currency (:currency account)})))

(defn- check-capability
  [policies kind action]
  (policy/check-capability policies kind {:action action}))

(defn- check-daily-count
  [policies kind aggregates]
  (policy/check-limit
   policies
   kind
   {:aggregate :count
    :window :time-window-daily
    :value (inc (get-in aggregates
                        [kind #{:bank-id :business-day}]))}))

(defn- check-amount
  "Check an amount limit on `kind` for `window`: the per-transaction
  cap (`:time-window-instant`, `value` = this payment) or the running
  daily value cap (`:time-window-daily`, `value` = today's total plus
  this payment)."
  [policies kind window currency value]
  (policy/check-limit
   policies
   kind
   {:aggregate :amount
    :window window
    :value {:currency currency :value value}}))

(defn internal-payment->transaction
  [data debtor-account creditor-account policies aggregates]
  (let [{:keys [bank-id idempotency-key debtor-account-id
                creditor-account-id currency amount
                reference]}
        data]
    (let-nom>
      [_ (ensure-account-operable debtor-account :debtor)
       _ (ensure-account-operable creditor-account :creditor)
       _ (ensure-distinct-accounts debtor-account-id creditor-account-id)
       _ (ensure-currency-matches currency debtor-account)
       _ (ensure-currency-matches currency creditor-account)
       _ (check-capability policies
                           :internal-payment
                           :internal-payment-action-submit)
       _ (check-daily-count policies :internal-payment aggregates)]
      (utility/assoc-some
       {:bank-id bank-id
        :idempotency-key idempotency-key
        :transaction-type :transaction-type-internal-transfer
        :currency currency
        :legs [{:account-id debtor-account-id
                :product-type (:product-type debtor-account)
                :balance-type :balance-type-default
                :balance-status :balance-status-posted
                :side :leg-side-debit
                :amount amount}
               {:account-id creditor-account-id
                :product-type (:product-type creditor-account)
                :balance-type :balance-type-default
                :balance-status :balance-status-posted
                :side :leg-side-credit
                :amount amount}]}
       :reference
       reference))))

(defn inbound-payment->transaction
  [data creditor-account suspense-account-id policies aggregates]
  (let [{:keys [scheme-transaction-id currency amount reference]} data
        {creditor-account-id :account-id bank-id :bank-id}
        creditor-account]
    (let-nom>
      [_ (ensure-currency-matches currency creditor-account)
       _ (check-capability policies
                           :inbound-payment
                           :inbound-payment-action-receive)
       _ (check-daily-count policies :inbound-payment aggregates)]
      (utility/assoc-some
       {:bank-id bank-id
        :idempotency-key scheme-transaction-id
        :transaction-type :transaction-type-inbound-transfer
        :currency currency
        :legs [{:account-id suspense-account-id
                :balance-type :balance-type-default
                :balance-status :balance-status-posted
                :side :leg-side-debit
                :amount amount}
               {:account-id creditor-account-id
                :product-type (:product-type creditor-account)
                :balance-type :balance-type-default
                :balance-status :balance-status-posted
                :side :leg-side-credit
                :amount amount}]}
       :reference
       reference))))

(defn new-inbound-payment
  [data creditor-account-id bank-id business-day transaction-id]
  (let [{:keys [scheme-transaction-id end-to-end-id scheme
                currency amount debtor-name reference]}
        data
        now (utility/now)]
    (utility/assoc-some
     {:payment-id (utility/generate-id "pmt")
      :scheme-transaction-id scheme-transaction-id
      :end-to-end-id end-to-end-id
      :scheme scheme
      :creditor-account-id creditor-account-id
      :bank-id bank-id
      :business-day business-day
      :currency currency
      :amount amount
      :transaction-id transaction-id
      :payment-status :inbound-payment-status-settled
      :debtor-name debtor-name
      :created-at now
      :updated-at now}
     :reference
     reference)))

(defn inbound-suspense->transaction
  "DEBIT 1100 cash-at-correspondent / CREDIT 2500 suspense — an inbound
  arrived for a BBAN that matches no account, so the funds land in
  suspense (a liability) pending reconciliation. GL-only legs."
  [data bank-id cash-at-correspondent-id suspense-account-id]
  (let [{:keys [scheme-transaction-id currency amount reference]} data]
    (utility/assoc-some
     {:bank-id bank-id
      :idempotency-key scheme-transaction-id
      :transaction-type :transaction-type-inbound-transfer
      :currency currency
      :legs [{:account-id cash-at-correspondent-id
              :balance-type :balance-type-default
              :balance-status :balance-status-posted
              :side :leg-side-debit
              :amount amount}
             {:account-id suspense-account-id
              :balance-type :balance-type-default
              :balance-status :balance-status-posted
              :side :leg-side-credit
              :amount amount}]}
     :reference
     reference)))

(defn suspended-inbound-payment
  "An inbound with no matching creditor account — recorded with status
  `suspended` and no creditor, the credit posted to 2500 suspense."
  [data bank-id business-day transaction-id]
  (let [{:keys [scheme-transaction-id end-to-end-id scheme
                currency amount debtor-name reference]}
        data
        now (utility/now)]
    (utility/assoc-some
     {:payment-id (utility/generate-id "pmt")
      :scheme-transaction-id scheme-transaction-id
      :end-to-end-id end-to-end-id
      :scheme scheme
      :bank-id bank-id
      :business-day business-day
      :currency currency
      :amount amount
      :transaction-id transaction-id
      :payment-status :inbound-payment-status-suspended
      :created-at now
      :updated-at now}
     :debtor-name
     debtor-name
     :reference
     reference)))

(defn held-inbound-payment
  "A held inbound — recorded `held` with the creditor resolved by BBAN, no
  posted transaction yet (funds are held at ClearBank, not ours). The held
  webhook carries no scheme transaction id, so a placeholder is generated;
  it is replaced with the real one on release."
  [data creditor-account-id bank-id business-day]
  (let [{:keys [end-to-end-id scheme currency amount debtor-name reference]}
        data
        now (utility/now)]
    (utility/assoc-some
     {:payment-id (utility/generate-id "pmt")
      :scheme-transaction-id (str "held-" (utility/uuidv7))
      :end-to-end-id end-to-end-id
      :scheme scheme
      :creditor-account-id creditor-account-id
      :bank-id bank-id
      :business-day business-day
      :currency currency
      :amount amount
      :payment-status :inbound-payment-status-held
      :created-at now
      :updated-at now}
     :debtor-name
     debtor-name
     :reference
     reference)))

(defn inbound-release->transaction
  "DEBIT 1100 cash-at-correspondent / CREDIT creditor — settle a held inbound
  on release. No policy/capability checks: the payment was already accepted
  when it was held."
  [held creditor-account cash-at-correspondent-id]
  (let [{:keys [bank-id currency amount payment-id]} held
        {creditor-account-id :account-id} creditor-account]
    {:bank-id bank-id
     :idempotency-key (str "release-in-" payment-id)
     :transaction-type :transaction-type-inbound-transfer
     :currency currency
     :legs [{:account-id cash-at-correspondent-id
             :balance-type :balance-type-default
             :balance-status :balance-status-posted
             :side :leg-side-debit
             :amount amount}
            {:account-id creditor-account-id
             :product-type (:product-type creditor-account)
             :balance-type :balance-type-default
             :balance-status :balance-status-posted
             :side :leg-side-credit
             :amount amount}]}))

(defn settled-from-held
  "Transition a held inbound to `settled` on release: stamp the real scheme
  transaction id and the posted transaction id."
  [held scheme-transaction-id transaction-id]
  (assoc held
         :payment-status :inbound-payment-status-settled
         :scheme-transaction-id scheme-transaction-id
         :transaction-id transaction-id
         :updated-at (utility/now)))

(defn returned-inbound-payment
  "Transition a held inbound to `returned` on decline — the funds went back
  to the remitter, so nothing posts on our books."
  [held]
  (assoc held
         :payment-status :inbound-payment-status-returned
         :updated-at (utility/now)))

(defn outbound-payment->transaction
  [data debtor-account pending-outbound-account-id policies aggregates]
  (let [{:keys [bank-id idempotency-key debtor-account-id
                currency amount reference]}
        data]
    (let-nom>
      [_ (ensure-account-operable debtor-account :debtor)
       _ (ensure-currency-matches currency debtor-account)
       _ (check-capability policies
                           :outbound-payment
                           :outbound-payment-action-send)
       _ (check-daily-count policies :outbound-payment aggregates)
       _ (check-amount policies
                       :outbound-payment
                       :time-window-instant
                       currency
                       amount)
       _ (check-amount policies
                       :outbound-payment
                       :time-window-daily
                       currency
                       (+ (get-in aggregates
                                  [:outbound-payment
                                   #{:bank-id :business-day :amount}])
                          amount))]
      ;; Reserve, don't post: the customer's funds move to their
      ;; pending-outgoing bucket (available drops, posted untouched) and
      ;; the bank's 1200 claim is likewise pending — the whole transfer
      ;; is in-flight until the scheme settles. Nothing hits a posted
      ;; bucket, so the trial balance is undisturbed at submit, and a
      ;; non-posted leg doesn't fan out a control leg.
      (utility/assoc-some
       {:bank-id bank-id
        :idempotency-key idempotency-key
        :transaction-type :transaction-type-outbound-transfer
        :currency currency
        :legs [{:account-id debtor-account-id
                :product-type (:product-type debtor-account)
                :balance-type :balance-type-default
                :balance-status :balance-status-pending-outgoing
                :side :leg-side-debit
                :amount amount}
               {:account-id pending-outbound-account-id
                :balance-type :balance-type-default
                :balance-status :balance-status-pending-outgoing
                :side :leg-side-credit
                :amount amount}]}
       :reference
       reference))))

(defn new-outbound-payment
  [data business-day transaction-id]
  (let [{:keys [idempotency-key bank-id debtor-account-id
                creditor-bban creditor-name scheme
                currency amount reference]}
        data
        now (utility/now)]
    (utility/assoc-some
     {:payment-id (utility/generate-id "pmt")
      :idempotency-key idempotency-key
      :scheme scheme
      :bank-id bank-id
      :business-day business-day
      :debtor-account-id debtor-account-id
      :creditor-bban creditor-bban
      :creditor-name creditor-name
      :currency currency
      :amount amount
      :payment-status :outbound-payment-status-pending
      :transaction-id transaction-id
      :created-at now
      :updated-at now}
     :reference
     reference)))

(defn completed-outbound-payment
  [payment]
  (assoc payment
         :payment-status :outbound-payment-status-completed
         :updated-at (utility/now)))

(defn held-outbound-payment
  [payment]
  (assoc payment
         :payment-status :outbound-payment-status-held
         :updated-at (utility/now)))

(defn failed-outbound-payment
  [payment cancellation-code cancellation-reason]
  (utility/assoc-some
   (assoc payment
          :payment-status :outbound-payment-status-failed
          :updated-at (utility/now))
   :cancellation-code cancellation-code
   :cancellation-reason cancellation-reason))

(defn outbound-settlement->transaction
  "The second hop, fired when the scheme confirms settlement. Converts the
  in-flight reservation into a real outflow: CREDIT the debtor's
  pending-outgoing (clear the reservation) and DEBIT its posted (the money
  now leaves), DEBIT 1200 pending-outbound (clear the in-flight claim) and
  CREDIT 1100 cash-at-correspondent (out to the scheme). Only the customer's
  posted debit and the 1100 credit touch posted buckets, and they tie — so
  the trial balance moves only now, at settlement. Carries the payment's
  reference so the settled debit reads as the customer wrote it."
  [payment debtor-account pending-outbound-id cash-at-correspondent-id]
  (let [{:keys [bank-id amount currency payment-id debtor-account-id
                reference]}
        payment]
    (utility/assoc-some
     {:bank-id bank-id
      :idempotency-key (str "settle-out-" payment-id)
      :transaction-type :transaction-type-outbound-transfer
      :currency currency
      :legs [{:account-id debtor-account-id
              :product-type (:product-type debtor-account)
              :balance-type :balance-type-default
              :balance-status :balance-status-pending-outgoing
              :side :leg-side-credit
              :amount amount}
             {:account-id debtor-account-id
              :product-type (:product-type debtor-account)
              :balance-type :balance-type-default
              :balance-status :balance-status-posted
              :side :leg-side-debit
              :amount amount}
             {:account-id pending-outbound-id
              :balance-type :balance-type-default
              :balance-status :balance-status-pending-outgoing
              :side :leg-side-debit
              :amount amount}
             {:account-id cash-at-correspondent-id
              :balance-type :balance-type-default
              :balance-status :balance-status-posted
              :side :leg-side-credit
              :amount amount}]}
     :reference
     reference)))

(defn outbound-reversal->transaction
  "Reverse an unsettled outbound when the scheme declines or returns it:
  CREDIT the debtor's pending-outgoing (release the reservation) and DEBIT
  1200 pending-outbound (clear the in-flight claim). The money never left
  the debtor's posted balance, so there is nothing posted to reverse — only
  the reservation is released. The mirror of `outbound-payment->transaction`."
  [payment debtor-account pending-outbound-account-id]
  (let [{:keys [bank-id amount currency payment-id debtor-account-id]}
        payment]
    {:bank-id bank-id
     :idempotency-key (str "reverse-out-" payment-id)
     :transaction-type :transaction-type-outbound-transfer
     :currency currency
     :legs [{:account-id pending-outbound-account-id
             :balance-type :balance-type-default
             :balance-status :balance-status-pending-outgoing
             :side :leg-side-debit
             :amount amount}
            {:account-id debtor-account-id
             :product-type (:product-type debtor-account)
             :balance-type :balance-type-default
             :balance-status :balance-status-pending-outgoing
             :side :leg-side-credit
             :amount amount}]}))

(defn new-internal-payment
  [data business-day transaction-id]
  (let [{:keys [idempotency-key bank-id debtor-account-id
                creditor-account-id currency amount
                reference]}
        data
        now (utility/now)]
    (utility/assoc-some
     {:payment-id (utility/generate-id "pmt")
      :idempotency-key idempotency-key
      :bank-id bank-id
      :business-day business-day
      :debtor-account-id debtor-account-id
      :creditor-account-id creditor-account-id
      :currency currency
      :amount amount
      :transaction-id transaction-id
      :created-at now
      :updated-at now}
     :reference
     reference)))
