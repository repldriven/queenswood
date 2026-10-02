(ns com.repldriven.queenswood.interest.scan
  (:require
    [com.repldriven.queenswood.interest.domain.account-run :as account-run]
    [com.repldriven.queenswood.interest.domain.run :as run]
    [com.repldriven.queenswood.interest.store :as store]

    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(def ^:private chunk-size
  "Accounts posted per transaction. FDB caps a transaction at 10MB and
  five seconds; an accrual writes two small records per account, so
  this sits well inside both while spreading the per-transaction cost
  over a hundred accounts instead of paying it for each."
  100)

(defn- post-account
  "One account's posting and its row, inside the chunk's transaction.
  Skips an account an earlier attempt already posted, and posts one it
  left pending or failed. Returns :done, :skipped, or an anomaly."
  [config ctx txn account balances]
  (let [{:keys [bank-id business-day account-kind account-fn]} ctx
        row (store/load-account-run txn
                                    bank-id
                                    business-day
                                    account-kind
                                    (:account-id account))]
    (if (and row (account-run/done? row))
      :skipped
      (let-nom> [result (account-fn config ctx txn account balances)
                 _ (store/save-account-run txn
                                           (account-run/done
                                            (account-run/new bank-id
                                                             business-day
                                                             account-kind
                                                             account
                                                             row)
                                            result))]
        :done))))

(defn- post-chunk
  "Posts a chunk of accounts in one transaction, so every balance write
  and every row flip in it commits together or none does. Returns the
  per-state counts, or an anomaly if any account in the chunk failed —
  in which case nothing in the chunk landed."
  [config ctx chunk]
  (store/transact
   config
   (fn [txn]
     (reduce (fn [counts [account balances]]
               (let [result (post-account config ctx txn account balances)]
                 (if (error/anomaly? result)
                   (reduced result)
                   (update counts result inc))))
             {:done 0 :skipped 0}
             chunk))))

(defn- mark-chunk-failed
  "Records every account in a failed chunk as FAILED, in its own
  transaction — the chunk's transaction has already rolled back, taking
  any DONE flip with it.

  The whole chunk is marked rather than the one account that raised.
  Accrual reads nothing and writes a row only this pass writes, so a
  failure here is a database that is unwell or a product whose accounts
  all fail the same way; isolating the offender would draw a
  distinction that does not exist in practice."
  [config ctx chunk anomaly]
  (let [{:keys [bank-id business-day account-kind]} ctx]
    (store/transact
     config
     (fn [txn]
       (reduce
        (fn [_ [account _balances]]
          (let [row (store/load-account-run txn
                                            bank-id
                                            business-day
                                            account-kind
                                            (:account-id account))
                result (store/save-account-run txn
                                               (account-run/failed
                                                (account-run/new
                                                 bank-id
                                                 business-day
                                                 account-kind
                                                 account
                                                 row)
                                                (error/kind anomaly)))]
            (when (error/anomaly? result) (reduced result))))
        nil
        chunk)))))

(defn- flush-chunk
  "Posts whatever the scan has accumulated and clears it. A failing
  chunk is marked FAILED and the scan carries on — aborting would leave
  every later account untouched and the run would never close."
  [config ctx state]
  (let [{:keys [chunk tally]} state]
    (if (empty? chunk)
      state
      (let [result (post-chunk config ctx chunk)]
        (assoc state
               :chunk []
               :tally (if (error/anomaly? result)
                        (do (mark-chunk-failed config ctx chunk result)
                            (update tally :failed + (count chunk)))
                        (-> tally
                            (update :done + (:done result))
                            (update :skipped + (:skipped result)))))))))

(defn post-accounts
  "Streams the bank's accounts with their balances and posts every
  eligible one through `ctx`'s `:account-fn`, a chunk of them per
  transaction. Returns the tally — `:done`, `:skipped`, `:failed`, and
  the `:seen` currency and product type pairs the ledger entries at
  close are owed for.

  One merged scan pairs each account with its balances, so a posting
  reads nothing of its own and computes on the figures the scan
  streamed in. No row is written ahead of the work: an account is
  either done, in which case a re-run skips it, or it is not, in which
  case a re-run redoes it — and a row saying the scan intended to reach
  it distinguishes neither."
  [config ctx]
  (let-nom>
    [state (cash-accounts/reduce-accounts-with-balances
            config
            (:bank-id ctx)
            (fn [state {:keys [account balances]}]
              (if-not (run/eligible-cash-account? account)
                state
                (let [state
                      (-> state
                          (update :chunk conj [account balances])
                          ;; The currency and product type of every
                          ;; account in scope, gathered as the scan
                          ;; runs, for the domain to reduce to the
                          ;; ledger entries its kind owes. Complete
                          ;; even on a re-run, because the scan visits
                          ;; every account each time and only the
                          ;; posting is skipped.
                          (update-in [:tally :seen]
                                     conj
                                     [(:currency account)
                                      (:product-type account)]))]
                  (if (< (count (:chunk state)) chunk-size)
                    state
                    (flush-chunk config ctx state)))))
            {:chunk []
             :tally {:done 0 :skipped 0 :failed 0 :seen #{}}})]
    (:tally (flush-chunk config ctx state))))
