(ns com.repldriven.queenswood.cash-account.store
  (:require
    [com.repldriven.queenswood.cash-account.changelog :as changelog]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

;; must match cash-account-query.store/store-name — same FDB store
(def ^:private store-name "cash-accounts")

(def transact fdb/transact)

(def uniqueness-violation? fdb/uniqueness-violation?)

(defn save-account
  [txn account changelog]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn store-name)]
       (let-nom>
         [_ (fdb/save-record store (schema/CashAccount->java account))
          entry (changelog/status-changed
                 (assoc changelog
                        :bank-id (:bank-id account)
                        :updated-at (:updated-at account)))
          _ (fdb/write-changelog txn
                                 store-name
                                 (:account-id account)
                                 entry)]
         nil)))
   :cash-account/save
   "Failed to save account"))

(defn allocate-payment-address
  "Allocates the next account number from the monotonic FDB
  counter (same pattern as the sort-code fountain in
  bank/store.clj). The counter only advances — it never
  rewinds or re-issues a number, so a closed account's number
  is retired forever, never handed to a later account. Same
  guarantee covers a payment address rotated away (QNS-20)."
  [txn counter]
  (fdb/transact txn
                (fn [txn]
                  (format "%08d"
                          (fdb/allocate-counter txn
                                                store-name
                                                "bank"
                                                "counters"
                                                counter)))
                :cash-account/allocate-payment-address
                "Failed to allocate payment address"))
