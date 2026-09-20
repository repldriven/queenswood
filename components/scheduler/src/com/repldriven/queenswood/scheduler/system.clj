(ns com.repldriven.queenswood.scheduler.system
  (:require
    [com.repldriven.queenswood.scheduler.core :as core]

    [com.repldriven.mono.system.interface :as system]))

;; Reconciles a cronut trigger per enabled job to the job rows, at
;; start and every minute after. The `:scheduler/scheduler` component
;; owns the Quartz lifecycle; this runner only wires triggers against
;; it. The resolved config map (record-db / record-store / schemas /
;; scheduler, plus the triggers this JVM registered) is the instance,
;; so consumers can run jobs through it.
(def ^:private runner
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance
                       (core/start! (assoc config :triggers (atom {})))))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component
                   :scheduler system/required-component}
   :system/instance-schema some?})

(system/defcomponents :bank-scheduler {:runner runner})
