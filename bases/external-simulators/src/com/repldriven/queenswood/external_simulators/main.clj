(ns com.repldriven.queenswood.external-simulators.main
  (:require
    [com.repldriven.queenswood.external-simulators.system]

    [com.repldriven.queenswood.clearbank-simulator.interface :as
     clearbank-simulator]
    [com.repldriven.queenswood.form3-simulator.interface :as form3-simulator]
    [com.repldriven.queenswood.modulr-simulator.interface :as
     modulr-simulator]
    [com.repldriven.queenswood.onfido-simulator.interface :as
     onfido-simulator]
    [com.repldriven.queenswood.uk-companies-house-simulator.interface :as
     ukch-simulator]
    [com.repldriven.queenswood.zyphe-simulator.interface :as zyphe-simulator]

    [com.repldriven.mono.cli.interface :as cli]
    [com.repldriven.mono.env.interface :as env]
    [com.repldriven.mono.error.interface :as error :refer [nom->]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.system.interface :as system])
  (:gen-class))

(def ^:private handlers
  {:clearbank-simulator-server clearbank-simulator/app
   :form3-simulator-server form3-simulator/app
   :modulr-simulator-server modulr-simulator/app
   :onfido-simulator-server onfido-simulator/app
   :uk-companies-house-simulator-server ukch-simulator/app
   :zyphe-simulator-server zyphe-simulator/app})

(defn- with-handlers
  "Fills the handler of each server the configuration declares, so the
  configuration alone decides which simulators run."
  [defs]
  (reduce-kv (fn [defs k app]
               (cond-> defs
                       (get-in defs [:system/defs k])
                       (assoc-in [:system/defs k :handler] app)))
             defs
             handlers))

(defn start
  [config-file profile]
  (nom-> (env/config config-file profile)
         system/defs
         with-handlers
         system/start))

(defn -main
  [& args]
  (log/info args)
  (let [{:keys [options exit-message ok?]}
        (cli/validate-args "bank-external-simulators" args)]
    (if exit-message
      (cli/exit ok? exit-message)
      (let [{:keys [config-file profile]} options
            sys (start config-file (keyword profile))]
        (if (error/anomaly? sys)
          (cli/exit false sys)
          (do (system/stop-on-shutdown sys)
              (log/info "System started successfully")
              @(promise)))))))
