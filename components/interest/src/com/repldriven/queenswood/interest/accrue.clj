(ns com.repldriven.queenswood.interest.accrue
  (:require
    [com.repldriven.queenswood.interest.domain.accrual :as accrual]
    [com.repldriven.queenswood.interest.domain.chart :as chart]
    [com.repldriven.queenswood.interest.store :as store]
    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.cash-account-product-query.interface :as
     products]
    [com.repldriven.queenswood.transaction.interface :as transactions]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- get-product-version
  "The account's pinned product version, memoised for the run.

  The map is built from the accounts as they stream rather than from
  the bank's current products: an account pins `product-id` and
  `version-id` when it opens, and a pinned version may be one the bank
  no longer offers, so enumerating what is on offer would miss
  accounts. A bank has dozens of versions in use rather than millions,
  so after the first few accounts this is a hash lookup.

  Scoped to the run rather than a TTL cache, because a pass wants one
  view of the rates it is applying — an entry expiring mid-pass would
  accrue two halves of the same bank at two different rates."
  [config versions bank-id account]
  (let [k [(:product-id account) (:version-id account)]]
    (if-some [hit (get @versions k)]
      hit
      (let [version (products/get-version config
                                          bank-id
                                          (:product-id account)
                                          (:version-id account))]
        (when-not (error/anomaly? version)
          (swap! versions assoc k version))
        version))))

(defn- accrue-account
  "One account's share of the day's interest, posted silently: the
  customer's interest-accrued bucket is credited and the sub-unit
  remainder carried, with no transaction record and no ledger leg.

  Accrual is not a statement line — what a customer sees is
  capitalisation — so the per-account transaction bought nothing and
  cost the two GL rows every accrual in the bank had to read and write.
  The bank's side is posted once for the run.

  Everything it computes on was frozen by the scan, so the whole
  account costs one unread write. That is sound because only this pass
  and capitalisation ever write an interest-accrued bucket; the
  principal it reads sits on the default bucket, which payments move
  and which this never writes."
  [config ctx txn account balances]
  (let [{:keys [bank-id account-id currency]} account]
    (let-nom>
      [version (get-product-version config (:versions ctx) bank-id account)
       accrued (accrual/accrue account-id
                               currency
                               balances
                               (:interest-rate-bps version))
       _ (when accrued
           (balances/accrue txn
                            (:balance accrued)
                            (:amount accrued)
                            (:closing-carry accrued)))]
      accrued)))

(defn- post-ledger-entry
  "The bank's ledger entry for one currency of an accrual run, posted
  once at close against the `gl` roles resolved for that currency. The
  total comes off the SUM index rather than a tally the pass kept,
  because a resumed run only posts what it processed itself while the
  index covers every row whichever attempt wrote it.

  `record-and-post` reads back on a duplicate idempotency key, so
  reaching close twice posts once."
  [config ctx gl currency]
  (let [{:keys [bank-id business-day account-kind]} ctx]
    (store/transact
     config
     (fn [txn]
       (let-nom>
         [total (store/sum-account-run-amounts txn
                                               bank-id
                                               business-day
                                               account-kind
                                               currency)
          transaction (accrual/ledger-transaction gl
                                                  bank-id
                                                  currency
                                                  total
                                                  business-day)
          _ (when transaction
              (transactions/record-and-post txn bank-id transaction))])))))

(def pass
  "Everything a run of this kind does differently from the other."
  {:policy-kind :accrual
   :run-kind :interest-run-kind-accrue
   :account-kind :interest-account-run-kind-accrue
   :account-fn accrue-account
   :gl-fn chart/accrual-accounts
   :entry-fn post-ledger-entry
   :entry-currency identity
   :entries-fn accrual/entries})
