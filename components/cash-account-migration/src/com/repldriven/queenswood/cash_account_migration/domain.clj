(ns com.repldriven.queenswood.cash-account-migration.domain
  (:require
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]))

(defn- ensure-named
  "A migration is read in a list beside others, so it needs a name an
  operator recognises. Rejected here rather than left to the record:
  protojure drops an empty string from the wire, and a proto2 required
  field missing from the bytes fails the Java parse — so a blank name
  would surface as a serialisation error rather than as the validation
  failure it is."
  [name]
  (when (str/blank? name)
    (error/reject :cash-account-migration/name-required
                  {:message "A migration needs a name"})))

(defn- ensure-target-published
  "Accounts may only be moved onto terms the bank has committed to. A
  draft is still being written and a discarded version was abandoned;
  neither is something to hold customers on."
  [target-version]
  (when-not (= :version-status-published (:status target-version))
    (error/reject :cash-account-migration/target-not-published
                  {:message "A migration's target version must be published"
                   :version-id (:version-id target-version)
                   :status (:status target-version)})))

(defn- ensure-same-product-type
  "The one compatibility rule. A savings product migrates to a savings
  product. Everything else that could differ between the two — currency,
  rate, balance layout, payment schemes — is settled per account when
  the migration runs, because an account that does not fit should be
  reported and left rather than invalidating the whole cohort."
  [source-version target-version]
  (let [source-type (:product-type source-version)
        target-type (:product-type target-version)]
    (when-not (= source-type target-type)
      (error/reject :cash-account-migration/product-type-mismatch
                    {:message "Source and target must be the same product type"
                     :source-product-type source-type
                     :target-product-type target-type}))))

(defn- ensure-distinct-target
  "A migration onto the version accounts already hold moves nobody, and
  reads as a mistake rather than as a deliberate no-op."
  [source-version-ids target-version-id]
  (when (= [target-version-id] (vec source-version-ids))
    (error/reject :cash-account-migration/target-is-source
                  {:message "A migration's target must differ from its source"
                   :version-id target-version-id})))

(defn- ensure-notice-precedes-move
  "Telling customers after they have moved is not notice. Whether the
  gap between the two is long enough is not decided here — the platform
  records what it was told."
  [notified-on due-on]
  (when (and notified-on due-on (< due-on notified-on))
    (error/reject :cash-account-migration/notice-after-due
                  {:message "Customers must be notified before accounts move"
                   :notified-on notified-on
                   :due-on due-on})))

(defn in-cohort?
  "Whether a migration reaches this account at all. The scan sees every
  account of the source product; a migration that named versions reaches
  only accounts pinned to those.

  Separate from eligibility because it is not a verdict — an account
  outside the cohort was never part of this migration, and recording a
  reason for it would bury the accounts that were."
  [migration account]
  (let [narrowing (:source-version-ids migration)]
    (and (= (:source-product-id migration) (:product-id account))
         (or (empty? narrowing)
             (contains? (set narrowing) (:version-id account))))))

(defn verdict
  "What a run decides about one account in the cohort: `:outcome`, and
  for an ineligible account the `:ineligibility` saying why.

  Eligibility is settled here, per account, rather than up front against
  the migration — a cohort where some accounts do not fit is ordinary,
  and the ones that do still move. The reason is what makes a preview
  worth reading.

  Order is deliberate. An account already on the target is reported as
  such rather than as an oddity, because that is what a re-run sees for
  everything an earlier run moved — it is how the pass stays idempotent
  without tracking what it did."
  [target-version account]
  (let [{:keys [status currency version-id]} account
        allowed #{(:currency target-version)}]
    (cond
     (= (:version-id target-version) version-id)
     {:outcome :cash-account-migration-outcome-ineligible
      :ineligibility :cash-account-migration-ineligibility-already-on-target}

     (not= :cash-account-status-opened status)
     {:outcome :cash-account-migration-outcome-ineligible
      :ineligibility :cash-account-migration-ineligibility-account-not-open}

     (not (contains? allowed currency))
     {:outcome :cash-account-migration-outcome-ineligible
      :ineligibility
      :cash-account-migration-ineligibility-currency-not-allowed}

     :else
     {:outcome :cash-account-migration-outcome-eligible})))

(defn new-migration
  "A migration in draft. `source-version` is any version of the source
  product — it is read for the product type only, which every version of
  a product shares. Nothing here moves an account: a draft is a
  statement of intent that previews run against and an operator
  approves."
  [data source-version target-version]
  (let [{:keys [bank-id name source-product-id source-version-ids notified-on
                due-on idempotency-key created-by]}
        data
        now (utility/now)]
    (let-nom>
      [_ (ensure-named name)
       _ (ensure-target-published target-version)
       _ (ensure-same-product-type source-version target-version)
       _ (ensure-distinct-target source-version-ids
                                 (:version-id target-version))
       _ (ensure-notice-precedes-move notified-on due-on)]
      (utility/assoc-some
       (utility/assoc-seq
        {:bank-id bank-id
         :migration-id (utility/generate-id "mig")
         :status :cash-account-migration-status-draft
         :name name
         :source-product-id source-product-id
         :target-product-id (:product-id target-version)
         :target-version-id (:version-id target-version)
         :idempotency-key idempotency-key
         :created-at now
         :created-by created-by
         :updated-at now}
        :source-version-ids
        source-version-ids)
       :notified-on notified-on
       :due-on due-on))))

(defn- ensure-status
  "A transition asserts the state it moves out of before anything else,
  so a second approval reads as a conflict rather than re-stamping a
  migration the scheduler may already be working through."
  [migration allowed action]
  (when-not (contains? allowed (:status migration))
    (error/reject :cash-account-migration/invalid-status
                  {:message (str "Migration cannot be " action)
                   :migration-id (:migration-id migration)
                   :status (:status migration)
                   :allowed allowed})))

(defn approve-migration
  "Approve a migration, making it work for the scheduler to pick up.

  Approval is where the notice window stops being optional. A draft may
  sit without dates for as long as an operator is still deciding, but
  approving one commits to moving customers' accounts, and doing that
  without having told them is the thing notice exists to prevent.

  Approval attaches to the migration — its source, target and selection
  — and not to any preview's numbers. Accounts open and close between
  approval and the commit, so the figures move; what was agreed does
  not."
  [migration actor]
  (let-nom>
    [_ (ensure-status migration
                      #{:cash-account-migration-status-draft}
                      "approved")
     _ (when-not (and (:notified-on migration) (:due-on migration))
         (error/reject
          :cash-account-migration/notice-required
          {:message
           "A migration needs a notice date and a due date before approval"
           :migration-id (:migration-id migration)
           :notified-on (:notified-on migration)
           :due-on (:due-on migration)}))]
    (let [now (utility/now)]
      (assoc migration
             :status :cash-account-migration-status-approved
             :approved-at now
             :approved-by actor
             :updated-at now))))

(defn cancel-migration
  "Cancel a migration, whether it was still a draft or already approved.

  Cancelling is how a migration that can no longer run leaves the work
  list, because the system cannot tell a target whose dates slipped from
  one nobody intends to use. A completed migration is not cancellable —
  accounts have moved, and saying otherwise would misdescribe them."
  [migration actor]
  (let-nom>
    [_ (ensure-status migration
                      #{:cash-account-migration-status-draft
                        :cash-account-migration-status-approved}
                      "cancelled")]
    (let [now (utility/now)]
      (assoc migration
             :status :cash-account-migration-status-cancelled
             :cancelled-at now
             :cancelled-by actor
             :updated-at now))))

(defn ensure-committable
  "Asserts a migration may be run for real, before anything moves.
  Separate from `complete-migration` so the guard fires ahead of the
  pass while the completion is stamped after it, rather than dating a
  migration completed before its accounts had moved."
  [migration]
  (ensure-status migration
                 #{:cash-account-migration-status-approved}
                 "committed"))

(defn complete-migration
  "Close an approved migration once a commit has run it. Separate from
  the run's own completion: a run can finish having moved nothing, and
  it is the migration that is done, not the pass."
  [migration]
  (let-nom>
    [_ (ensure-status migration
                      #{:cash-account-migration-status-approved}
                      "completed")]
    (let [now (utility/now)]
      (assoc migration
             :status :cash-account-migration-status-completed
             :completed-at now
             :updated-at now))))

(defn due?
  "Whether an approved migration is work for `business-day`.

  Derived every time the job looks rather than latched, because the
  dates it is measured against can move and the migration moves with
  them. A target whose window has not opened is not due today, which is
  a different thing from being dead."
  [migration target-version business-day]
  (let [{:keys [effective-from effective-to]} target-version]
    (and (= :cash-account-migration-status-approved (:status migration))
         (some? (:due-on migration))
         (<= (:due-on migration) business-day)
         (or (nil? effective-from) (<= effective-from business-day))
         (or (nil? effective-to) (< business-day effective-to)))))

(def ^:private abandoned-after-ms
  "How long a preview runs before another preview takes it for one whose
  pass died and goes ahead."
  (* 15 60 1000))

(defn check-no-preview-running
  "A rejection when one of `runs` is a preview still running at `now`,
  else nil. A preview running for longer than `abandoned-after-ms` does
  not count."
  [runs now]
  (when-let [running
             (first (filter (fn [{:keys [dry-run status started-at]}]
                              (and dry-run
                                   (= :cash-account-migration-run-status-running
                                      status)
                                   (< now
                                      (+ started-at
                                         abandoned-after-ms))))
                            runs))]
    (error/reject :cash-account-migration/preview-running
                  {:message "A preview of this migration is already running"
                   :migration-id (:migration-id running)
                   :run-id (:run-id running)})))

(defn new-run
  "A run opens as running, and is closed by whatever finishes it. A
  preview and a commit are the same record: `dry-run?` decides only
  whether accounts are written, never what is decided about them."
  [migration business-day dry-run?]
  {:bank-id (:bank-id migration)
   :run-id (str (utility/uuidv7))
   :migration-id (:migration-id migration)
   :status :cash-account-migration-run-status-running
   :dry-run dry-run?
   :business-day business-day
   :started-at (utility/now)})

(def moved-verdict
  "What a commit records for an account it actually moved, onto the
  migration's target. Distinct from the eligible verdict a preview
  writes: eligible is a forecast, migrated is a fact, and the two sit in
  the same table."
  {:outcome :cash-account-migration-outcome-migrated})

(defn failed-verdict
  "What a commit records for an account it could not move. One account's
  failure is its own — the pass carries on, and the row says which one
  and why."
  [anomaly]
  {:outcome :cash-account-migration-outcome-failed
   :failure-reason (str (error/kind anomaly))})

(defn account-verdict
  "One account's row, as the run saw it, with the source version it was
  on."
  [run account decision]
  (let [{:keys [outcome ineligibility failure-reason]} decision]
    (utility/assoc-some
     {:bank-id (:bank-id run)
      :run-id (:run-id run)
      :account-id (:account-id account)
      :migration-id (:migration-id run)
      :outcome outcome
      :source-version-id (:version-id account)
      :created-at (utility/now)}
     :ineligibility ineligibility
     :failure-reason failure-reason)))

(defn close-run
  "Closes a run with what it decided. Counts ride on the run because a
  reader wants them before any individual verdict, and totalling every
  row to find them is the expensive way."
  [run tally]
  (utility/assoc-some (assoc run
                             :status
                             :cash-account-migration-run-status-completed
                             :finished-at (utility/now))
                      :accounts-seen (:seen tally)
                      :accounts-moved (:moved tally)
                      :accounts-ineligible (:ineligible tally)
                      :accounts-failed (:failed tally)))

(defn fail-run
  "Closes a run that could not finish. Distinct from a run that finished
  having found every account ineligible — that one decided about them
  all and succeeded."
  [run anomaly]
  (assoc run
         :status :cash-account-migration-run-status-failed
         :finished-at (utility/now)
         :failure-reason (str (error/kind anomaly))))
