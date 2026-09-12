(ns com.repldriven.queenswood.api.jobs.components
  (:require
    [com.repldriven.queenswood.api.jobs.coercion :as coercion]
    [com.repldriven.queenswood.api.jobs.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :refer
     [components-registry]]))

(def JobId
  [:re
   {:title "JobId"
    :json-schema/example examples/JobId
    :description "Stable per-bank job slug, e.g. \"daily-interest\"."}
   #"^[a-z0-9]+(-[a-z0-9]+)*$"])

(def RunId
  [:re
   {:title "RunId"
    :json-schema/example examples/RunId
    :description "Time-ordered run identifier (uuidv7)."}
   #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"])

(def Periodicity
  (coercion/periodicity-enum-schema {:json-schema/example "daily"}))

(def JobTaskKind
  (coercion/task-kind-enum-schema {:json-schema/example "accrue"}))

(def RunStatus
  (coercion/run-status-enum-schema {:json-schema/example "running"}))

(def TriggerSource
  (coercion/trigger-source-enum-schema {:json-schema/example "scheduled"}))

(def TaskStatus
  (coercion/task-status-enum-schema {:json-schema/example "succeeded"}))

(def MonthlyDay
  (coercion/monthly-day-enum-schema {:json-schema/example "last"}))

(def JobKind (coercion/kind-enum-schema {:json-schema/example "user"}))

(def Job
  [:map {:json-schema/example examples/Job}
   [:bank-id [:ref "BankId"]]
   [:job-id [:ref "JobId"]]
   [:name [:ref "Name"]]
   [:kind [:ref "JobKind"]]
   [:task-kinds [:vector [:ref "JobTaskKind"]]]
   [:periodicity [:ref "Periodicity"]]
   ;; Present only for monthly jobs.
   [:monthly-day {:optional true} [:ref "MonthlyDay"]]
   ;; The cadences this job's tasks permit; the editable set for a user
   ;; job (a system job's cadence is fixed regardless).
   [:allowed-periodicities [:vector [:ref "Periodicity"]]]
   ;; Minutes past midnight (UTC) the job fires on each scheduled day.
   [:run-time-minutes [:int {:min 0 :max 1439}]]
   [:enabled boolean?]
   [:last-run-at {:optional true} [:ref "Timestamp"]]
   [:next-run-at {:optional true} [:ref "Timestamp"]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at [:ref "Timestamp"]]])

(def JobList
  [:map {:json-schema/example examples/JobList}
   [:jobs [:vector [:ref "Job"]]]])

(def TaskRun
  "What one task of a run did. `records-processed` / `records-failed`
  are whatever that task's pass counts — accounts, for every task there
  is today. A skipped task carries neither, and no timings: the run
  never reached it."
  [:map {:json-schema/example examples/TaskRun}
   [:label string?]
   [:status [:ref "TaskStatus"]]
   [:started-at {:optional true} [:ref "Timestamp"]]
   [:finished-at {:optional true} [:ref "Timestamp"]]
   [:error {:optional true} string?]
   [:records-processed {:optional true} nat-int?]
   [:records-failed {:optional true} nat-int?]])

(def Run
  [:map {:json-schema/example examples/Run}
   [:bank-id [:ref "BankId"]]
   [:run-id [:ref "RunId"]]
   [:job-id [:ref "JobId"]]
   [:status [:ref "RunStatus"]]
   [:trigger-source [:ref "TriggerSource"]]
   [:started-at [:ref "Timestamp"]]
   [:finished-at {:optional true} [:ref "Timestamp"]]
   [:tasks-total nat-int?]
   [:tasks-completed nat-int?]
   [:current-task {:optional true} string?]
   [:expected-end-at {:optional true} [:ref "Timestamp"]]
   [:error {:optional true} string?]
   [:tasks [:vector [:ref "TaskRun"]]]])

(def RunList
  [:map {:json-schema/example examples/RunList}
   [:runs [:vector [:ref "Run"]]]])

(def JobScheduleUpdate
  "Editable schedule fields. All optional — an omitted field keeps its
  current value. Toggling `enabled` is the pause/resume control.
  `monthly-day` applies to monthly jobs. A system job's cadence is fixed:
  only `run-time-minutes` is editable on one."
  [:map {:closed true :json-schema/example examples/JobScheduleUpdate}
   [:periodicity {:optional true} [:ref "Periodicity"]]
   [:monthly-day {:optional true} [:ref "MonthlyDay"]]
   [:run-time-minutes {:optional true} [:int {:min 0 :max 1439}]]
   [:enabled {:optional true} boolean?]])

(def registry
  (components-registry [#'JobId #'RunId #'Periodicity #'JobTaskKind #'RunStatus
                        #'TriggerSource #'TaskStatus #'MonthlyDay #'JobKind
                        #'Job #'JobList #'TaskRun #'Run #'RunList
                        #'JobScheduleUpdate]))
