(ns com.repldriven.queenswood.interest.scan
  (:require
    [com.repldriven.queenswood.interest.domain.account-run :as account-run]
    [com.repldriven.queenswood.interest.domain.run :as run]
    [com.repldriven.queenswood.interest.store :as store]

    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(def ^:private chunk-size
  "Accounts posted per transaction. FDB caps a transaction at 10MB and
  five seconds; a posting writes a few small records per account, so
  this sits well inside both while spreading the per-transaction cost
  over a hundred accounts instead of paying it for each."
  100)

(defn- post-chunk
  "Posts a chunk of accounts in one transaction, so every posting and
  every row flip in it commits together or none does. Skips an account
  an earlier attempt already posted, and hands the rest, as pairs of
  account and balances, to `ctx`'s `:chunk-fn`, which posts them and
  returns each one's outcome by account id. Returns the per-state
  counts, or an anomaly if the chunk failed — in which case nothing in
  it landed."
  [config ctx chunk]
  (let [{:keys [bank-id business-day account-kind chunk-fn]} ctx]
    (store/transact
     config
     (fn [txn]
       (let-nom>
         [rows (store/load-account-runs txn
                                        bank-id
                                        business-day
                                        account-kind
                                        (mapv (comp :account-id first) chunk))
          todo (into []
                     (remove (fn [[account]]
                               (some-> (get rows (:account-id account))
                                       account-run/done?)))
                     chunk)
          outcomes (if (seq todo) (chunk-fn config ctx txn todo) {})
          _ (reduce (fn [_ [account]]
                      (let [id (:account-id account)
                            result (store/save-account-run
                                    txn
                                    (account-run/done
                                     (account-run/new bank-id
                                                      business-day
                                                      account-kind
                                                      account
                                                      (get rows id))
                                     (get outcomes id)))]
                        (when (error/anomaly? result) (reduced result))))
                    nil
                    todo)]
         {:done (count todo) :skipped (- (count chunk) (count todo))})))))

(defn- mark-chunk-failed
  "Records every account in a failed chunk as FAILED, in its own
  transaction — the chunk's transaction has already rolled back, taking
  any DONE flip with it. An account an earlier attempt finished stays
  DONE, so the next attempt skips it rather than posting it twice.

  The whole chunk is marked rather than the one account that raised.
  A chunk appends legs and rows nothing else writes, so a failure here
  is a database that is unwell or a product whose accounts all fail the
  same way; isolating the offender would draw a distinction that does
  not exist in practice."
  [config ctx chunk anomaly]
  (let [{:keys [bank-id business-day account-kind]} ctx]
    (store/transact
     config
     (fn [txn]
       (let-nom>
         [rows (store/load-account-runs txn
                                        bank-id
                                        business-day
                                        account-kind
                                        (mapv (comp :account-id first) chunk))]
         (reduce
          (fn [_ [account]]
            (let [row (get rows (:account-id account))
                  result (when-not (some-> row
                                           account-run/done?)
                           (store/save-account-run
                            txn
                            (account-run/failed
                             (account-run/new bank-id
                                              business-day
                                              account-kind
                                              account
                                              row)
                             (error/kind anomaly))))]
              (when (error/anomaly? result) (reduced result))))
          nil
          chunk))))))

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
  eligible one through `ctx`'s `:chunk-fn`, a chunk of them per
  transaction. Returns the tally — `:done`, `:skipped` and `:failed`.

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
                (let [state (update state :chunk conj [account balances])]
                  (if (< (count (:chunk state)) chunk-size)
                    state
                    (flush-chunk config ctx state)))))
            {:chunk []
             :tally {:done 0 :skipped 0 :failed 0}})]
    (:tally (flush-chunk config ctx state))))
