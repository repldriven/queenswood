(ns com.repldriven.queenswood.cash-account-migration.core
  (:require
    [com.repldriven.queenswood.cash-account-migration.domain :as domain]
    [com.repldriven.queenswood.cash-account-migration.store :as store]
    [com.repldriven.queenswood.cash-account-product-query.interface :as
     products]
    [com.repldriven.queenswood.cash-account-query.interface :as accounts]
    [com.repldriven.queenswood.cash-account.interface :as cash-accounts]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- source-version
  "Any version of the source product, read for its product type. Every
  version of a product shares one, so which is immaterial — but a
  product with no versions at all is a source that cannot be checked."
  [txn bank-id product-id]
  (let [versions (products/get-versions txn bank-id {:product-id product-id})]
    (cond
     (error/anomaly? versions)
     versions

     (empty? versions)
     (error/reject :cash-account-migration/source-product-not-found
                   {:message "Source product has no versions"
                    :bank-id bank-id
                    :product-id product-id})

     :else
     (first versions))))

(defn create-migration
  "Author a migration in draft. Reads the source and target inside one
  transaction so the product types they are checked against are the ones
  the migration is written from.

  Retried creates read back rather than authoring a second migration:
  the idempotency key is unique-indexed per bank, and a duplicate would
  otherwise mean two approvals against one set of accounts."
  [txn data]
  (store/transact
   txn
   (fn [txn]
     (let [{:keys [bank-id source-product-id target-product-id
                   target-version-id idempotency-key]}
           data]
       (let-nom>
         [existing (if idempotency-key
                     (store/find-by-idempotency-key txn
                                                    bank-id
                                                    idempotency-key)
                     nil)]
         (if existing
           existing
           (let-nom>
             [source (source-version txn bank-id source-product-id)
              target (products/get-version txn
                                           bank-id
                                           target-product-id
                                           target-version-id)
              migration (domain/new-migration data source target)
              _ (store/save-migration txn migration)]
             migration)))))))

(defn get-migration
  [txn bank-id migration-id]
  (let-nom>
    [migration (store/load-migration txn bank-id migration-id)]
    (or migration
        (error/reject :cash-account-migration/not-found
                      {:message "Migration not found"
                       :bank-id bank-id
                       :migration-id migration-id}))))

(defn list-migrations
  ([txn bank-id]
   (list-migrations txn bank-id {}))
  ([txn bank-id opts]
   (store/list-migrations txn bank-id opts)))

(def ^:private chunk-size
  "Accounts whose moves and verdicts commit together. Both are small
  records, so this sits well inside FDB's transaction limits while
  spreading the per-transaction cost over a hundred accounts rather than
  paying it for each.

  The two travel in one transaction deliberately: a verdict saying an
  account moved, committed apart from the move, is a report of something
  that did not happen."
  100)

(defn- tally-verdict
  [tally decision]
  (let [outcome (:outcome decision)]
    (cond-> (update tally :seen inc)
            (= :cash-account-migration-outcome-ineligible outcome)
            (update :ineligible inc)

            (= :cash-account-migration-outcome-failed outcome)
            (update :failed inc)

            (contains? #{:cash-account-migration-outcome-eligible
                         :cash-account-migration-outcome-migrated}
                       outcome)
            (update :moved inc))))

(defn- apply-entry
  "One account inside the chunk's transaction: moved if this is a commit
  and it was found eligible, then its verdict written. Returns the
  verdict actually recorded.

  A move that comes back an anomaly becomes that account's failed
  verdict rather than raising. The commonest cause is an account whose
  status changed between the scan and the write, which is one account's
  problem — the rest of the chunk still moves."
  [txn ctx [account decision]]
  (let [{:keys [run target policies dry-run?]} ctx
        final (if (or dry-run?
                      (not= :cash-account-migration-outcome-eligible
                            (:outcome decision)))
                decision
                (let [moved (cash-accounts/migrate-account txn
                                                           account
                                                           target
                                                           policies)]
                  (if (error/anomaly? moved)
                    (domain/failed-verdict moved)
                    (domain/moved-verdict target))))
        verdict (domain/account-verdict run account final)
        saved (store/save-account-run txn verdict)]
    (if (error/anomaly? saved) saved final)))

(defn- fail-chunk
  "Records every account of a rolled-back chunk as failed, in a fresh
  transaction — the chunk's own has gone, taking any move in it along.

  The whole chunk is marked rather than one account. A per-account
  failure was already caught and recorded as such inside the
  transaction, so reaching here means the transaction itself could not
  commit, and that is not a property of any one account."
  [config ctx chunk anomaly]
  (let [{:keys [run]} ctx
        verdict (domain/failed-verdict anomaly)]
    (store/transact config
                    (fn [txn]
                      (reduce (fn [_ [account _decision]]
                                (store/save-account-run
                                 txn
                                 (domain/account-verdict run account verdict)))
                              nil
                              chunk)))))

(defn- flush-chunk
  "Applies a chunk in one transaction — every eligible account moved, on
  a commit, and every verdict written — then clears it and advances the
  tally by what the chunk actually decided."
  [config ctx state]
  (let [{:keys [chunk]} state]
    (if (empty? chunk)
      state
      ;; Short-circuits on the first anomaly so the chunk comes back as
      ;; one rather than as a vector with an anomaly buried in it, which
      ;; would tally as a decision that never happened.
      (let [decided (store/transact
                     config
                     (fn [txn]
                       (reduce (fn [acc entry]
                                 (let [verdict (apply-entry txn ctx entry)]
                                   (if (error/anomaly? verdict)
                                     (reduced verdict)
                                     (conj acc verdict))))
                               []
                               chunk)))]
        (if (error/anomaly? decided)
          (do (fail-chunk config ctx chunk decided)
              (-> state
                  (assoc :chunk [])
                  (update-in [:tally :seen] + (count chunk))
                  (update-in [:tally :failed] + (count chunk))))
          (-> state
              (assoc :chunk [])
              (update :tally #(reduce tally-verdict % decided))))))))

(defn- evaluate-accounts
  "Streams the bank's accounts and decides about every one the migration
  reaches, a chunk of them per transaction. Returns the tally.

  Eligibility is decided as the scan streams; the move and the verdict
  are applied together when the chunk flushes. Nothing is re-read per
  account — the scan already holds it, the target version is one for the
  whole cohort, and policy was resolved once for the run. That is what
  makes the pass linear in transactions rather than in accounts.

  A dry run and a commit differ only in whether an eligible verdict is
  carried out. The streaming, the cohort test and the eligibility order
  are the same code, because a preview that ran different logic would be
  evidence of nothing."
  [config ctx]
  (let-nom>
    [state (accounts/reduce-accounts-with-balances
            config
            (:bank-id (:migration ctx))
            (fn [state {:keys [account]}]
              (if-not (domain/in-cohort? (:migration ctx) account)
                state
                (let [decision (domain/verdict (:target ctx) account)
                      state (update state :chunk conj [account decision])]
                  (if (< (count (:chunk state)) chunk-size)
                    state
                    (flush-chunk config ctx state)))))
            {:chunk []
             :tally {:seen 0 :moved 0 :ineligible 0 :failed 0}})]
    (:tally (flush-chunk config ctx state))))

(defn- cohort-size
  "How many accounts the migration reaches, off the by-version index
  rather than by scanning them.

  This is the cohort, not the eligible set — some of these accounts
  will turn out to be closed, or hold a currency the target forbids.
  Bounding the cohort is the point: what a limit on a migration
  constrains is how much of the bank it is pointed at, and that is
  knowable before the pass rather than only after it."
  [txn bank-id migration]
  (let-nom>
    ;; Absent narrowing means every version of the source product, the
    ;; same reading `in-cohort?` gives it. Counting only the named ones
    ;; would size an unnarrowed migration at zero and never bind.
    [version-ids (if (seq (:source-version-ids migration))
                   (:source-version-ids migration)
                   (let-nom> [versions (products/get-versions
                                        txn
                                        bank-id
                                        (:source-product-id migration))]
                     (mapv :version-id versions)))]
    (reduce (fn [total version-id]
              (let [n (accounts/count-by-version txn bank-id version-id)]
                (if (error/anomaly? n) (reduced n) (+ total n))))
            0
            version-ids)))

(defn- check-commit-limit
  "Refuses a migration pointed at more accounts than its tier allows,
  before a single one moves. A limit discovered part-way through would
  leave a half-migrated cohort, which is worse than not starting."
  [txn bank-id migration policies]
  (let-nom>
    [size (cohort-size txn bank-id migration)
     _ (policy/check-limit policies
                           :cash-account-migration
                           {:aggregate :count
                            :window :time-window-instant
                            :action :cash-account-migration-action-commit
                            :value size})]
    size))

(defn- check-preview-limit
  "Bounds how often a bank may dry-run a migration in a day. A preview
  reads every account of a product, so it is the cheapest thing here to
  ask for and the most expensive to serve."
  [txn bank-id business-day policies]
  (let-nom>
    [today (store/count-previews-on txn bank-id business-day)]
    (policy/check-limit policies
                        :cash-account-migration
                        {:aggregate :count
                         :window :time-window-daily
                         :action :cash-account-migration-action-preview
                         :value (inc today)})))

(defn- run-migration
  "One pass over a migration's cohort, previewing or committing. Opens a
  run, evaluates every account, and closes the run with what it decided.

  A run is closed as failed only when the pass itself could not finish.
  A pass that decided about every account and moved none succeeded — it
  is the accounts that were ineligible, not the run."
  [txn bank-id migration-id business-day dry-run?]
  (let-nom>
    [migration (get-migration txn bank-id migration-id)
     target (products/get-version txn
                                  bank-id
                                  (:target-product-id migration)
                                  (:target-version-id migration))
     ;; Resolved once for the run, as the interest pass does. A pass
     ;; wants one view of the rules it is applying — re-resolving per
     ;; account would let a policy change mid-cohort and move half a
     ;; bank under one set of rules and half under another.
     policies (policy/get-effective-policies txn {:bank-id bank-id})
     ;; Both limits are checked before the run is opened, so a refused
     ;; pass leaves no run behind saying it started.
     _ (if dry-run?
         (check-preview-limit txn bank-id business-day policies)
         (check-commit-limit txn bank-id migration policies))
     run (domain/new-run migration business-day dry-run?)
     _ (store/save-run txn run)
     tally (evaluate-accounts txn
                              {:migration migration
                               :target target
                               :policies policies
                               :run run
                               :dry-run? dry-run?})]
    (if (error/anomaly? tally)
      (let-nom> [_ (store/save-run txn (domain/fail-run run tally))] tally)
      (let-nom> [closed (domain/close-run run tally)
                 _ (store/save-run txn closed)]
        closed))))

(defn preview-migration
  "Evaluate a migration without moving anything. Writes a run and a
  verdict per account in the cohort, and returns the closed run.

  Previews may be re-run as often as wanted, including after approval:
  accounts open and close and balances move, so a preview is a forecast
  of what a commit would do rather than a promise, and being able to
  look again is what makes that drift visible."
  [txn bank-id migration-id business-day]
  (run-migration txn bank-id migration-id business-day true))

(defn approve-migration
  [txn bank-id migration-id]
  (store/transact
   txn
   (fn [txn]
     (let-nom>
       [migration (get-migration txn bank-id migration-id)
        approved (domain/approve-migration migration)
        _ (store/save-migration txn approved)]
       approved))))

(defn cancel-migration
  [txn bank-id migration-id]
  (store/transact
   txn
   (fn [txn]
     (let-nom>
       [migration (get-migration txn bank-id migration-id)
        cancelled (domain/cancel-migration migration)
        _ (store/save-migration txn cancelled)]
       cancelled))))

(defn commit-migration
  "Run a migration for real, then close it. Returns the run.

  The migration completes only when the pass finished. A pass that could
  not run leaves it approved, so the next business day picks it up again
  rather than stranding a migration nobody moved."
  [txn bank-id migration-id business-day]
  (let-nom>
    [migration (get-migration txn bank-id migration-id)
     _ (domain/ensure-committable migration)
     run (run-migration txn bank-id migration-id business-day false)
     completed (domain/complete-migration migration)
     _ (store/save-migration txn completed)]
    run))

(defn- target-of
  [txn bank-id migration]
  (products/get-version txn
                        bank-id
                        (:target-product-id migration)
                        (:target-version-id migration)))

(defn- due-migrations
  "The bank's migrations that are work for `business-day`. Derived every
  time rather than recorded, so a migration waiting on a date becomes due
  when the date arrives without anything having touched it.

  A target that cannot be read is skipped rather than raised: it makes
  that one migration not-due today, which is exactly what a closed window
  does, and the rest of the work list is unaffected."
  [txn bank-id business-day]
  (let-nom>
    [migrations (store/list-migrations txn bank-id {})]
    (into []
          (filter (fn [migration]
                    (let [target (target-of txn bank-id migration)]
                      (and (not (error/anomaly? target))
                           (domain/due? migration target business-day)))))
          migrations)))

(defn run-due-migrations
  "Commit every migration the bank has that is due on `business-day`.

  One migration failing does not stop the others — each is its own unit
  of work, and the summary says which ones ran and what they moved.

  `:accounts-processed` and `:accounts-failed` are the keys the
  scheduler reads onto a task's record, and carry the same meaning they
  do for the interest pass: processed is every account the pass decided
  about, whether or not it moved, and failed is counted apart rather
  than inside it. The migration-shaped figures ride alongside for a
  reader who wants the split."
  [txn bank-id business-day]
  (let-nom>
    [due (due-migrations txn bank-id business-day)]
    (let [totals
          (reduce
           (fn [summary migration]
             (let [run (commit-migration txn
                                         bank-id
                                         (:migration-id migration)
                                         business-day)]
               (if (error/anomaly? run)
                 (-> summary
                     (update :migrations inc)
                     (update :failed-migrations conj (:migration-id migration)))
                 (-> summary
                     (update :migrations inc)
                     (update :moved + (:accounts-moved run 0))
                     (update :ineligible + (:accounts-ineligible run 0))
                     (update :accounts-failed + (:accounts-failed run 0))))))
           {:migrations 0
            :moved 0
            :ineligible 0
            :accounts-failed 0
            :failed-migrations []}
           due)]
      (assoc totals
             :accounts-processed
             (+ (:moved totals) (:ineligible totals))))))

(defn get-run
  [txn bank-id run-id]
  (let-nom>
    [run (store/load-run txn bank-id run-id)]
    (or run
        (error/reject :cash-account-migration/run-not-found
                      {:message "Migration run not found"
                       :bank-id bank-id
                       :run-id run-id}))))

(defn list-runs
  [txn bank-id migration-id]
  (store/list-runs-by-migration txn bank-id migration-id))

(defn list-run-accounts
  ([txn bank-id run-id]
   (list-run-accounts txn bank-id run-id {}))
  ([txn bank-id run-id opts]
   (store/list-account-runs txn bank-id run-id opts)))
