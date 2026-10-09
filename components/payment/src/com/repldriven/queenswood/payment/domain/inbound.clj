(ns com.repldriven.queenswood.payment.domain.inbound
  (:require
    [com.repldriven.queenswood.payment.domain.checks :as checks]
    [com.repldriven.queenswood.payment.domain.scheme :as scheme]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(defn check-inbound-acceptance
  [data creditor-account policies aggregates]
  (let [{:keys [currency]} data]
    (let-nom>
      [_ (checks/ensure-currency-matches currency creditor-account)
       _ (checks/check-capability policies
                                  :inbound-payment
                                  :inbound-payment-action-receive)
       _ (checks/check-daily-count policies :inbound-payment aggregates)]
      nil)))

(defn inbound-payment->transaction
  [data creditor-account cash-account-id policies aggregates]
  (let [{:keys [scheme-transaction-id currency amount reference]} data
        {creditor-account-id :account-id bank-id :bank-id}
        creditor-account]
    (let-nom>
      [_ (check-inbound-acceptance data creditor-account policies aggregates)]
      (utility/assoc-some
       {:bank-id bank-id
        :idempotency-key scheme-transaction-id
        :transaction-type :transaction-type-inbound-transfer
        :currency currency
        :scheme-account-id creditor-account-id
        :legs [{:account-id cash-account-id
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

(defn- arrived
  "An inbound as it arrives in `status`, recording when it reached it."
  [data bank-id business-day status at-key]
  (let [{:keys [scheme-transaction-id end-to-end-id scheme
                currency amount debtor-name reference]}
        data
        now (utility/now)]
    (utility/assoc-some {:bank-id bank-id
                         :payment-id (utility/generate-id "pmt")
                         :status status
                         :scheme-type (scheme/scheme-type scheme)
                         :amount amount
                         :currency currency
                         :end-to-end-id end-to-end-id
                         :scheme-transaction-id scheme-transaction-id
                         :business-day business-day
                         at-key now
                         :created-at now}
                        :debtor-name
                        debtor-name
                        :reference
                        reference)))

(defn- moved
  [payment status at-key]
  (let [now (utility/now)]
    (assoc payment
           :status
           status
           at-key
           now
           :updated-at
           now)))

(defn new-inbound-payment
  [data creditor-account-id bank-id business-day transaction-id]
  (assoc (arrived data
                  bank-id
                  business-day
                  :inbound-payment-status-settled
                  :settled-at)
         :creditor-account-id creditor-account-id
         :transaction-id transaction-id))

(defn inbound-suspense->transaction
  "DEBIT 1100 cash-at-correspondent / CREDIT 2500 suspense — an inbound
  the receiving account could not take lands in suspense (a liability)
  pending reconciliation. GL-only legs, naming the receiving account the
  scheme paid it into."
  [data bank-id cash-at-correspondent-id suspense-account-id
   receiving-account-id]
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
     :scheme-account-id
     receiving-account-id
     :reference
     reference)))

(defn suspended-inbound-payment
  "An inbound with no matching creditor account — recorded with status
  `suspended` and no creditor, the credit posted to 2500 suspense, and the
  ISO 20022 reason it was parked for."
  [data bank-id business-day transaction-id refusal]
  (utility/assoc-some (assoc (arrived data
                                      bank-id
                                      business-day
                                      :inbound-payment-status-suspended
                                      :suspended-at)
                             :transaction-id transaction-id
                             :suspended-reason-code (:reason-code refusal))
                      :suspended-reason
                      (:reason refusal)))

(defn held-inbound-payment
  "A held inbound — recorded `held` with the creditor resolved by BBAN, no
  posted transaction yet (funds are held at ClearBank, not ours). The held
  webhook carries no scheme transaction id, so a placeholder is generated;
  it is replaced with the real one on release."
  [data creditor-account-id bank-id business-day]
  (assoc (arrived data
                  bank-id
                  business-day
                  :inbound-payment-status-held
                  :held-at)
         :creditor-account-id creditor-account-id
         :scheme-transaction-id (str "held-" (utility/uuidv7))))

(defn release-count
  [today-count held business-day]
  (if (= business-day (:business-day held))
    (dec today-count)
    today-count))

(defn inbound-release->transaction
  "DEBIT 1100 cash-at-correspondent / CREDIT creditor — settle a held inbound
  on release, once it passes the checks a settlement runs."
  [held creditor-account cash-at-correspondent-id policies aggregates]
  (let [{:keys [bank-id currency amount payment-id]} held
        {creditor-account-id :account-id} creditor-account]
    (let-nom>
      [_ (check-inbound-acceptance held creditor-account policies aggregates)]
      {:bank-id bank-id
       :idempotency-key (str "release-in-" payment-id)
       :transaction-type :transaction-type-inbound-transfer
       :currency currency
       :scheme-account-id creditor-account-id
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
               :amount amount}]})))

(defn settled-from-held
  "Transition a held inbound to `settled` on release: stamp the real scheme
  transaction id and the posted transaction id."
  [held scheme-transaction-id transaction-id]
  (assoc (moved held :inbound-payment-status-settled :settled-at)
         :scheme-transaction-id scheme-transaction-id
         :transaction-id transaction-id))

(defn suspended-from-held
  [held scheme-transaction-id transaction-id refusal]
  (utility/assoc-some (assoc (moved held
                                    :inbound-payment-status-suspended
                                    :suspended-at)
                             :scheme-transaction-id scheme-transaction-id
                             :transaction-id transaction-id
                             :suspended-reason-code (:reason-code refusal))
                      :suspended-reason
                      (:reason refusal)))

(def ^:private closed-statuses
  #{:cash-account-status-closing :cash-account-status-closed})

(defn account-refusal
  "Why an inbound to `account` is refused at admission, as the ISO 20022
  reason `{:reason-code :reason}`: `AC01` where no account holds the
  address, `AC04` for one closing or closed, `AC06` for one not opened
  or suspended. Nil for an opened account."
  [account]
  (cond
   (nil? account)
   {:reason-code "AC01" :reason "No account holds the address"}

   (contains? closed-statuses (:status account))
   {:reason-code "AC04" :reason "The account is closed"}

   (not (checks/operable? account))
   {:reason-code "AC06" :reason "The account is not open for payments"}

   :else
   nil))

(defn acceptance-refusal
  "The ISO 20022 reason for a refusal from `check-inbound-acceptance`:
  `AM03` for a currency the account does not hold, `AG01` for anything a
  policy forbids."
  [refused]
  (if (= :payment/currency-mismatch (error/kind refused))
    {:reason-code "AM03" :reason (:message (error/payload refused))}
    {:reason-code "AG01" :reason (:message (error/payload refused))}))

(defn admitted-inbound-payment
  "An admitted inbound: recorded `admitted` for the creditor the BBAN
  resolved, with nothing posted until the scheme settles it. The
  admission carries no scheme transaction id, so a placeholder is
  generated; the settlement replaces it."
  [data creditor-account-id bank-id business-day]
  (assoc (arrived data
                  bank-id
                  business-day
                  :inbound-payment-status-admitted
                  :admitted-at)
         :creditor-account-id creditor-account-id
         :scheme-transaction-id (str "admitted-" (utility/uuidv7))))

(defn admitted-inbound->transaction
  "DEBIT 1100 cash-at-correspondent / CREDIT creditor — settle an admitted
  inbound. Its checks ran at admission, so none run again."
  [admitted creditor-account cash-at-correspondent-id]
  (let [{:keys [bank-id currency amount payment-id reference]} admitted
        {creditor-account-id :account-id} creditor-account]
    (utility/assoc-some
     {:bank-id bank-id
      :idempotency-key (str "admit-in-" payment-id)
      :transaction-type :transaction-type-inbound-transfer
      :currency currency
      :scheme-account-id creditor-account-id
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
              :amount amount}]}
     :reference
     reference)))

(defn select-hold-to-return
  [holds end-to-end-id]
  (case (count holds)
    0 nil
    1 (first holds)
    (error/fail :payment/ambiguous-hold
                {:message "More than one open hold carries the end-to-end id"
                 :end-to-end-id end-to-end-id
                 :payment-ids (mapv :payment-id holds)})))

(defn returned-inbound-payment
  "Transition a held inbound to `returned` on decline — the funds went back
  to the remitter, so nothing posts on our books."
  [held]
  (moved held :inbound-payment-status-returned :returned-at))

(defn return-failed-inbound-payment
  "A suspended inbound the provider did not send back: its return failed,
  the money still in suspense, carrying why."
  [payment reason]
  (utility/assoc-some (moved payment
                             :inbound-payment-status-return-failed
                             :return-failed-at)
                      :return-failed-reason
                      reason))

(def ^:private in-suspense
  #{:inbound-payment-status-suspended :inbound-payment-status-return-failed})

(defn in-suspense?
  "True where the inbound's money is in 2500 suspense: suspended, or its
  return failed."
  [payment]
  (contains? in-suspense (:status payment)))

(defn returns-inbound?
  "True where the provider declares that an inbound may be returned."
  [payment-provider]
  (boolean (some #{"inbound"} (:returns payment-provider))))

(defn return-payment
  "The `return-payment` command sending a suspended inbound back to its
  sender, for the reason it was parked."
  [payment]
  (let [{:keys [payment-id end-to-end-id scheme-transaction-id amount
                currency suspended-reason-code suspended-reason]}
        payment]
    {:payment-id payment-id
     :end-to-end-id end-to-end-id
     :scheme-transaction-id scheme-transaction-id
     :amount amount
     :currency currency
     :reason-code suspended-reason-code
     :reason suspended-reason}))

(defn inbound-return->transaction
  "DEBIT 2500 suspense / CREDIT 1100 cash-at-correspondent — a suspended
  inbound sent back to its sender, emptying suspense of it."
  [payment cash-at-correspondent-id suspense-account-id]
  (let [{:keys [bank-id currency amount payment-id creditor-account-id
                reference]}
        payment]
    (utility/assoc-some
     {:bank-id bank-id
      :idempotency-key (str "return-in-" payment-id)
      :transaction-type :transaction-type-inbound-return
      :currency currency
      :legs [{:account-id suspense-account-id
              :balance-type :balance-type-default
              :balance-status :balance-status-posted
              :side :leg-side-debit
              :amount amount}
             {:account-id cash-at-correspondent-id
              :balance-type :balance-type-default
              :balance-status :balance-status-posted
              :side :leg-side-credit
              :amount amount}]}
     :scheme-account-id
     creditor-account-id
     :reference
     reference)))
