(ns com.repldriven.queenswood.scheduler.core
  (:require
    [com.repldriven.queenswood.scheduler.domain :as domain]
    [com.repldriven.queenswood.scheduler.store :as store]

    [com.repldriven.queenswood.audit.interface :as audit]
    [com.repldriven.queenswood.cash-account-migration.interface :as migrations]
    [com.repldriven.queenswood.interest.interface :as interest]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.scheduler.interface :as scheduler]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.tools.logging :as log]))

;; Code-defined registry of preset tasks. Each `:run` takes the FDB+
;; interfaces config, the bank id, and an as-of date (epoch-day), and
;; returns a result map or an anomaly.
(def ^:private task-registry
  {:scheduler-task-kind-accrue
   {:label "accrue"
    :run (fn [config bank-id as-of-date]
           (interest/accrue-day config
                                {:bank-id bank-id :as-of-date as-of-date}))}
   :scheduler-task-kind-capitalize {:label "capitalize"
                                    :run (fn [config bank-id as-of-date]
                                           (interest/capitalize-accrued
                                            config
                                            {:bank-id bank-id
                                             :as-of-date as-of-date}))}
   ;; The only thing that moves accounts between products. Authoring a
   ;; migration and approving one both move nothing; this task is what
   ;; carries an approved, due migration out.
   :scheduler-task-kind-account-migration
   {:label "migrate"
    :run (fn [config bank-id as-of-date]
           (migrations/run-due-migrations config bank-id as-of-date))}})

(defn- task-label
  [task-kind]
  (get-in task-registry [task-kind :label] (name task-kind)))

(defn- trigger-id
  "Scheduler trigger name for a job. cronut keys jobs within one shared
  group, so the bank id is folded in to keep names unique across banks."
  [job]
  (str (:bank-id job) "/" (:job-id job)))

(declare register!)

(def ^:private default-jobs
  "Default scheduled jobs seeded into every bank at provisioning,
  loaded once from the bank-resources classpath. Templates carry no
  bank id / timestamps — those are stamped at seed time."
  (let [path "scheduler/jobs.edn"
        url (io/resource path)]
    (when (nil? url)
      ;; nosemgrep: no-raw-throw
      (throw (ex-info "Default scheduler jobs resource missing" {:path path})))
    ;; nosemgrep: no-edn-serialization — reads a classpath resource
    (edn/read-string (slurp url))))

;; --- queries --------------------------------------------------------------

(defn list-jobs
  [config bank-id opts]
  (store/list-jobs config bank-id opts))

(defn get-job
  [config bank-id job-id]
  (store/get-job config bank-id job-id))

(defn get-run
  [config bank-id run-id]
  (store/get-run config bank-id run-id))

(defn list-runs
  [config bank-id job-id]
  (store/list-runs-by-job config bank-id job-id))

;; --- run engine -----------------------------------------------------------

(defn- last-completed-run
  [runs]
  (first (filter (fn [run] (= :scheduler-run-status-completed (:status run)))
                 runs)))

(defn- job-event
  "The audit entry of an act on `job` by `actor` under the API request
  `request-id`."
  [kind job actor request-id details]
  {:bank-id (:bank-id job)
   :kind kind
   :subject-kind :audit-subject-kind-job
   :subject-id (:job-id job)
   :request-id request-id
   :details details
   :created-by actor})

(defn- open-run
  "Write `run` as running, with the end the job's last success suggests,
  in the transaction that reads the job's runs, unless
  `domain/period-refusal` refuses it, which it then returns. Returns the
  run written."
  [config job run request-id]
  (store/transact config
                  (fn [txn]
                    (let-nom>
                      [runs (store/list-runs-by-job txn
                                                    (:bank-id run)
                                                    (:job-id run))
                       _ (domain/period-refusal job runs (:created-at run))
                       opened (utility/assoc-some
                               run
                               :expected-end-at
                               (domain/expected-end-at (:created-at run)
                                                       (last-completed-run
                                                        runs)))
                       _ (store/save-run txn opened)
                       _ (audit/record txn
                                       (job-event
                                        :audit-kind-job-run-forced
                                        job
                                        (:created-by run)
                                        request-id
                                        {:run-id (:run-id run)}))]
                      opened))
                  :scheduler/open-run
                  "Failed to open scheduler run"))

(defn- ran
  "`run` as it stands after a save: with `tasks`, changed at `now`."
  [run tasks now]
  (assoc run :tasks tasks :updated-at now))

(defn- record-run
  "Record on the job that it ran at `ran-at`, and when it fires next,
  reading the job afresh in the same transaction, so an edit committed
  while the run was in progress is kept."
  [config bank-id job-id ran-at]
  (store/transact
   config
   (fn [txn]
     (let-nom> [job (store/get-job txn bank-id job-id)]
       (when job
         (store/save-job txn
                         (assoc job
                                :last-run-at ran-at
                                :next-run-at (scheduler/next-fire-at
                                              (:schedule job)
                                              ran-at))))))
   :scheduler/record-run
   "Failed to record the job's run"))

(defn run-job
  "Execute `job`'s tasks sequentially, recording a `SchedulerRun` that
  lists each task as it starts and as it ends. Refuses with
  `:scheduler/period-already-run` when the job has run, or is running,
  in the period it starts in; otherwise opens the run as running and
  finishes it completed or — on the first task anomaly — failed, the
  remaining tasks skipped. `as-of-date` is today (epoch-day); the
  underlying interest tasks are idempotent and guarded by the
  daily-limit policy, so a re-run records a failed run rather than
  double-posting. A forced run records the `actor` that forced it, and
  the bank's audit log the API request `request-id`.
  Returns the final run map, or the task anomaly on failure."
  [config bank-id job trigger-source actor request-id]
  (let [created-at (utility/now)
        as-of-date (utility/today)
        task-kinds (vec (:task-kinds job))
        unopened (utility/assoc-some {:bank-id bank-id
                                      :run-id (str (utility/uuidv7))
                                      :status :scheduler-run-status-running
                                      :trigger-source trigger-source
                                      :job-id (:job-id job)
                                      :tasks-total (count task-kinds)
                                      :tasks []
                                      :created-at created-at}
                                     :created-by
                                     (some-> actor
                                             (select-keys [:kind
                                                           :principal-id])))]
    (let-nom>
      [base (open-run config job unopened request-id)]
      (loop [[task-kind & more] task-kinds
             tasks []]
        (if (nil? task-kind)
          (let [now (utility/now)
                run (assoc (ran base tasks now)
                           :status
                           :scheduler-run-status-completed)]
            (store/save-run config run)
            (record-run config bank-id (:job-id job) created-at)
            run)
          (let [label (task-label task-kind)
                task (domain/started-task label (utility/now))
                _ (store/save-run config
                                  (ran base (conj tasks task) (utility/now)))
                run-fn (get-in task-registry [task-kind :run])
                result (if run-fn
                         (run-fn config bank-id as-of-date)
                         (error/reject :scheduler/unknown-task
                                       {:message "No run registered for task"
                                        :task-kind task-kind}))
                finished-at (utility/now)]
            (if (error/anomaly? result)
              (let [now (utility/now)
                    run (assoc (ran base
                                    (into (conj tasks
                                                (domain/failed-task task
                                                                    finished-at
                                                                    result))
                                          (domain/skipped-tasks
                                           (map task-label more)))
                                    now)
                               :status :scheduler-run-status-failed
                               :failure-reason (error/format-anomaly result))]
                (store/save-run config run)
                result)
              (recur more
                     (conj tasks
                           (domain/finished-task task
                                                 finished-at
                                                 result))))))))))

(defn force-start
  "Run `job-id` now with trigger source forced, in the period now falls
  in, which a run that is running or completed there refuses. The run
  records the `actor` that forced it, and the bank's audit log the
  request `opts` names."
  ([config bank-id job-id actor]
   (force-start config bank-id job-id actor {}))
  ([config bank-id job-id actor opts]
   (let [job (store/get-job config bank-id job-id)]
     (cond
      (error/anomaly? job)
      job

      (nil? job)
      (error/reject :scheduler/job-not-found
                    {:bank-id bank-id :job-id job-id})

      :else
      (run-job config
               bank-id
               job
               :scheduler-trigger-source-forced
               actor
               (:request-id opts))))))

(defn update-schedule
  "Edit a job's schedule or status, the schedule within what its tasks
  allow, recording the `actor` who did, and in the bank's audit log
  the request `opts` names.
  Persists the change, recomputes `next-run-at`, and reflects it on the
  live trigger when a scheduler is present in `config`."
  [config bank-id job-id
   {:keys [schedule status] :as edits}
   actor opts]
  (let [job (store/get-job config bank-id job-id)]
    (cond
     (error/anomaly? job)
     job

     (nil? job)
     (error/reject :scheduler/job-not-found
                   {:bank-id bank-id :job-id job-id})

     :else
     (let [schedule (or schedule (:schedule job))
           status (or status (:status job))
           now (utility/now)]
       (let-nom> [_ (domain/validate-system-edits job edits)
                  _ (domain/validate-schedule (:task-kinds job) schedule now)]
         (let [by (select-keys actor [:kind :principal-id])
               updated (assoc job
                              :schedule schedule
                              :status status
                              :next-run-at (scheduler/next-fire-at schedule now)
                              :updated-at now
                              :updated-by by)
               kind (cond (domain/paused? job status)
                          :audit-kind-job-paused

                          (domain/resumed? job status)
                          :audit-kind-job-resumed

                          :else
                          :audit-kind-job-schedule-updated)
               result (store/transact
                       config
                       (fn [txn]
                         (let-nom> [_ (store/save-job txn updated)]
                           (audit/record txn
                                         (job-event kind
                                                    job
                                                    by
                                                    (:request-id opts)
                                                    {:schedule schedule}))))
                       :scheduler/update-schedule
                       "Failed to update the job's schedule")]
           (if (error/anomaly? result)
             result
             (do
               (when (:scheduler config)
                 (register! config updated))
               updated))))))))

;; --- seeding + trigger registration --------------------------------------

(defn- template->job
  "A seeded job for `bank-id` from a `jobs.edn` template: the template's
  fields, the bank, the next fire, the time and, where one seeded it,
  the `actor`."
  [bank-id template now actor]
  (utility/assoc-some (assoc template
                             :bank-id bank-id
                             :next-run-at (scheduler/next-fire-at
                                           (:schedule template)
                                           now)
                             :created-at now)
                      :created-by
                      (some-> actor
                              (select-keys [:kind :principal-id]))))

(defn seed-jobs
  "Seed the bank's default scheduled jobs (FDB only — no triggers).
  Idempotent: re-seeding overwrites by `[bank_id, job_id]`. Runs inside
  the caller's transaction so a failed row rolls bank creation back.
  `actor`, who creates the bank, creates each job."
  [txn bank-id actor]
  (reduce (fn [_ template]
            (let [result (store/save-job txn
                                         (template->job bank-id
                                                        template
                                                        (utility/now)
                                                        actor))]
              (if (error/anomaly? result) (reduced result) nil)))
          nil
          default-jobs))

(defn- seed-missing-jobs
  "The templates a bank has no row for, seeded. A template added to
  `jobs.edn` after the bank was created reaches it here, and a row an
  operator has edited is never overwritten, since only the missing are
  written. Answers the rows written."
  [config bank-id jobs]
  (let [present (set (map :job-id jobs))]
    (reduce (fn [written template]
              (if (present (:job-id template))
                written
                (let [job (template->job bank-id template (utility/now) nil)
                      result (store/save-job config job)]
                  (if (error/anomaly? result)
                    (do (log/error "Scheduler could not seed a job"
                                   {:bank-id bank-id
                                    :job-id (:job-id template)
                                    :error (error/format-anomaly result)})
                        written)
                    (do (log/info "Scheduler seeded a job"
                                  {:bank-id bank-id :job-id (:job-id job)})
                        (conj written job))))))
            []
            default-jobs)))

(defn- fire
  "What a trigger runs: the job as its row reads now, not as it read
  when the trigger was registered, so an edit made elsewhere is honoured
  at the next fire even before the reconcile that re-registers it. A
  row that has gone, or been paused, runs nothing."
  [config bank-id job-id]
  (let [job (store/get-job config bank-id job-id)]
    (cond
     (error/anomaly? job)
     (log/error "Scheduler could not load the job to run"
                {:bank-id bank-id
                 :job-id job-id
                 :error (error/format-anomaly job)})

     (nil? job)
     (log/info "Scheduler skipped a job whose row has gone"
               {:bank-id bank-id :job-id job-id})

     (not= :scheduler-job-status-active (:status job))
     (log/info "Scheduler skipped a job that is paused"
               {:bank-id bank-id :job-id job-id})

     :else
     (do (log/info "Scheduler running a job"
                   {:bank-id bank-id :job-id job-id})
         (run-job config
                  bank-id
                  job
                  :scheduler-trigger-source-scheduled
                  nil
                  nil)))))

(defn- register!
  "Make the live trigger for `job` match its row: registered on the
  row's cron where the job is active, and absent where it is not.
  `:triggers` remembers what this JVM registered, keyed by trigger id,
  so an unchanged row costs nothing. A `config` with no `:triggers`
  registers without remembering, which the next reconcile corrects."
  [config job]
  (let [sched (:scheduler config)
        triggers (:triggers config)
        id (trigger-id job)
        want (when (= :scheduler-job-status-active (:status job))
               (:schedule job))
        have (when triggers (get @triggers id))]
    (cond
     (and want (not= want have))
     (let [result (scheduler/schedule sched
                                      id
                                      want
                                      #(fire config
                                             (:bank-id job)
                                             (:job-id job)))]
       (if (error/anomaly? result)
         (log/error
          "Scheduler could not register a trigger"
          {:trigger id :cron want :error (error/format-anomaly result)})
         (do (log/info "Scheduler registered a trigger"
                       {:trigger id :cron want})
             (when triggers (swap! triggers assoc id want)))))

     (and (nil? want) have)
     (do (scheduler/unschedule sched id)
         (log/info "Scheduler removed a trigger" {:trigger id})
         (swap! triggers dissoc id))

     :else
     nil)))

(defn reconcile!
  "Make the live triggers match the job rows: seed a job the rows lack
  for a bank that has any, register a trigger for every active job
  that has none or whose cron changed, and remove one for a job that is
  paused. The rows are the truth, wherever they were written — a bank
  created after this runner started, a job added to `jobs.edn` after
  the bank, and an edit made through the API in another JVM all reach
  the live scheduler here. Run at start and every minute after.
  `config` must carry `:scheduler` and `:triggers`."
  [config]
  (let [jobs (store/list-all-jobs config)]
    (if (error/anomaly? jobs)
      (log/error "Scheduler could not list the jobs to reconcile"
                 {:error (error/format-anomaly jobs)})
      (let [by-bank (group-by :bank-id jobs)
            seeded (mapcat (fn [[bank-id bank-jobs]]
                             (seed-missing-jobs config bank-id bank-jobs))
                    by-bank)]
        (doseq [job (concat jobs seeded)]
          (register! config job))
        nil))))

(def ^:private sweep-cron "Every minute, on the minute." "0 * * * * ?")

(defn start!
  "Reconcile once, then every minute: the runner's start. Answers the
  config, which is the runner's instance."
  [config]
  (reconcile! config)
  (let [result (scheduler/schedule (:scheduler config)
                                   "reconcile"
                                   sweep-cron
                                   #(reconcile! config))]
    (when (error/anomaly? result)
      (log/error "Scheduler could not register its reconcile sweep"
                 {:error (error/format-anomaly result)})))
  config)
