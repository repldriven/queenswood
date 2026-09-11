(ns com.repldriven.queenswood.interest.capitalize
  (:require
    [com.repldriven.queenswood.interest.domain.capitalization :as
     capitalization]
    [com.repldriven.queenswood.interest.domain.chart :as chart]
    [com.repldriven.queenswood.interest.store :as store]
    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.transaction.interface :as transactions]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- capitalize-account
  "One account's accrued interest swept into its spendable balance.

  Keeps its per-account transaction, because that transaction is the
  customer's statement line — the one thing about interest they
  actually see. What it drops is the control legs: fanning out per
  account made every capitalisation in the bank read and write the
  2400 payable and the deposit control, the same two-row contention
  accrual was taken off. The bank's side is posted once per currency
  and product type at close.

  Unlike accrual this cannot be an unread write. It credits the default
  bucket, which payments move, so `apply-legs` reads inside the posting
  transaction and the read-modify-write there is load-bearing."
  [_config ctx txn account balances]
  (let [{:keys [account-id bank-id currency]} account]
    (let-nom>
      [swept (capitalization/sweep bank-id
                                   account-id
                                   currency
                                   balances
                                   (:business-day ctx))
       _ (when swept
           (let-nom>
             [recorded (transactions/record-transaction txn
                                                        (:transaction swept))
              _ (balances/apply-legs txn
                                     bank-id
                                     (:legs recorded)
                                     (:transaction-type recorded))]))]
      swept)))

(defn- post-ledger-entry
  "The bank's ledger entry for one currency and product type of a
  capitalisation run, posted against the `gl` roles resolved for that
  currency. Split by product type because the credit side is whichever
  deposit control that product rolls into, so one entry per currency
  could not name them all and still balance."
  [config ctx gl [currency product-type]]
  (let [{:keys [bank-id business-day account-kind]} ctx]
    (store/transact
     config
     (fn [txn]
       (let-nom>
         [total (store/sum-account-run-amounts-by-product
                 txn
                 bank-id
                 business-day
                 account-kind
                 currency
                 product-type)
          transaction (capitalization/ledger-transaction gl
                                                         bank-id
                                                         currency
                                                         product-type
                                                         total
                                                         business-day)
          _ (when transaction
              (transactions/record-and-post txn bank-id transaction))])))))

(def pass
  "Everything a run of this kind does differently from the other."
  {:policy-kind :capitalize
   :run-kind :interest-run-kind-capitalize
   :account-kind :interest-account-run-kind-capitalize
   :account-fn capitalize-account
   :gl-fn chart/capitalization-accounts
   :entry-fn post-ledger-entry
   :entry-currency first
   :entries-fn capitalization/entries})
