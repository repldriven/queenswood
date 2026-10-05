(ns com.repldriven.queenswood.interest.capitalize
  (:require
    [com.repldriven.queenswood.interest.domain.capitalization :as
     capitalization]
    [com.repldriven.queenswood.interest.domain.chart :as chart]
    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.transaction.interface :as transactions]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- capitalize-account
  "One account's accrued interest swept into its spendable balance.

  Its transaction is the customer's statement line — the one thing
  about interest they actually see — and debits interest payable for
  what it pays, so the bank's side moves with it. The deposit control
  is the sum of its sub-ledger, so the customer's credit moves that
  too. 2400 is read and rewritten by every account in the chunk, inside
  the chunk's one transaction; payments never write it.

  Unlike accrual this cannot be an unread write. It credits the default
  bucket, which payments move, so `apply-legs` reads inside the posting
  transaction and the read-modify-write there is load-bearing."
  [_config ctx txn account balances]
  (let [{:keys [account-id bank-id currency]} account]
    (let-nom>
      [gl (chart/accounts-for (:gl ctx) bank-id currency)
       swept (capitalization/sweep bank-id
                                   account-id
                                   currency
                                   (:payable gl)
                                   balances
                                   (:business-day ctx))
       _ (when swept
           (let-nom>
             [recorded (transactions/record-transaction txn
                                                        (:transaction swept))
              _ (balances/apply-legs txn
                                     bank-id
                                     (:legs swept)
                                     (:transaction-type recorded)
                                     {:policies (policy/platform-policies
                                                 (:policies ctx))})]))]
      swept)))

(def pass
  "Everything a run of this kind does differently from the other."
  {:policy-kind :capitalize
   :run-kind :interest-run-kind-capitalize
   :account-kind :interest-account-run-kind-capitalize
   :account-fn capitalize-account
   :gl-fn chart/capitalization-accounts})
