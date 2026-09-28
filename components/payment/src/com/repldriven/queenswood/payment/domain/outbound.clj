(ns com.repldriven.queenswood.payment.domain.outbound
  (:require
    [com.repldriven.queenswood.payment.domain.checks :as checks]

    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(defn check-scheme
  [scheme payment-provider]
  (let [{:keys [schemes]} payment-provider]
    (when-not (some #{scheme} schemes)
      (error/reject :payment/unsupported-scheme
                    {:message (str "The payment provider does not carry "
                                   scheme)
                     :scheme scheme
                     :schemes schemes}))))

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

(defn outbound-payment->transaction
  [data debtor-account pending-outbound-account-id policies aggregates]
  (let [{:keys [bank-id idempotency-key debtor-account-id
                currency amount reference]}
        data]
    (let-nom>
      [_ (checks/ensure-account-operable debtor-account :debtor)
       _ (checks/ensure-currency-matches currency debtor-account)
       _ (checks/check-capability policies
                                  :outbound-payment
                                  :outbound-payment-action-send)
       _ (checks/check-daily-count policies :outbound-payment aggregates)
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

(def ^:private settleable-outbound-statuses
  #{:outbound-payment-status-pending :outbound-payment-status-held})

(defn settleable-outbound?
  [payment]
  (contains? settleable-outbound-statuses (:payment-status payment)))

(def ^:private reportable-outbound-statuses
  #{:outbound-payment-status-pending :outbound-payment-status-held})

(defn republishable-outbound?
  [payment]
  (= :outbound-payment-status-pending (:payment-status payment)))

(defn sweep-actions
  [payments now {:keys [republish-after-ms report-after-ms]}]
  (let [age-ms (fn [payment] (- now (:created-at payment)))]
    {:republish (filterv (fn [payment]
                           (and (republishable-outbound? payment)
                                (> (age-ms payment) republish-after-ms)))
                         payments)
     :report (into []
                   (comp (filter (fn [payment]
                                   (and (contains? reportable-outbound-statuses
                                                   (:payment-status payment))
                                        (> (age-ms payment) report-after-ms))))
                         (map (fn [payment]
                                {:payment-id (:payment-id payment)
                                 :bank-id (:bank-id payment)
                                 :payment-status (:payment-status payment)
                                 :age-ms (age-ms payment)})))
                   payments)}))

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

(def ^:private failure-kinds
  {:failure-kind-declined :outbound-payment-failure-kind-declined
   :failure-kind-refused :outbound-payment-failure-kind-refused
   :failure-kind-undelivered :outbound-payment-failure-kind-undelivered})

(defn failed-outbound-payment
  [payment rejection]
  (let [{:keys [failure-kind reason-code cancellation-reason]} rejection]
    (utility/assoc-some
     (assoc payment
            :payment-status :outbound-payment-status-failed
            :failure-kind (get failure-kinds
                               failure-kind
                               :outbound-payment-failure-kind-declined)
            :failure-reason-code (or reason-code "NARR")
            :updated-at (utility/now))
     :failure-reason
     cancellation-reason)))

(defn returned-outbound-payment
  [payment returned]
  (let [{:keys [reason-code reason]} returned]
    (utility/assoc-some
     (assoc payment
            :payment-status :outbound-payment-status-returned
            :return-reason-code reason-code
            :updated-at (utility/now))
     :return-reason
     reason)))

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
      :scheme-account-id debtor-account-id
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

(defn outbound-return->transaction
  "Bring back a settled outbound the scheme returned: DEBIT 1100
  cash-at-correspondent (the money arrives back from the scheme) and
  CREDIT the debtor's posted balance, by the amount the scheme returned.
  The scheme moved the money through the debtor's account, so a provider
  holding a balance for each account has credited that account already."
  [payment debtor-account cash-at-correspondent-id amount]
  (let [{:keys [bank-id currency payment-id debtor-account-id reference]}
        payment]
    (utility/assoc-some
     {:bank-id bank-id
      :idempotency-key (str "return-out-" payment-id)
      :transaction-type :transaction-type-outbound-return
      :currency currency
      :scheme-account-id debtor-account-id
      :legs [{:account-id cash-at-correspondent-id
              :balance-type :balance-type-default
              :balance-status :balance-status-posted
              :side :leg-side-debit
              :amount amount}
             {:account-id debtor-account-id
              :product-type (:product-type debtor-account)
              :balance-type :balance-type-default
              :balance-status :balance-status-posted
              :side :leg-side-credit
              :amount amount}]}
     :reference
     reference)))
