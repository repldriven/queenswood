(ns com.repldriven.queenswood.monolith.main
  (:require
    [com.repldriven.queenswood.monolith.system]

    [com.repldriven.queenswood.api.api :as api]
    [com.repldriven.queenswood.clearbank-adapter.interface :as
     clearbank-adapter]
    [com.repldriven.queenswood.clearbank-simulator.interface :as
     clearbank-simulator]
    [com.repldriven.queenswood.form3-adapter.interface :as form3-adapter]
    [com.repldriven.queenswood.form3-simulator.interface :as form3-simulator]
    [com.repldriven.queenswood.modulr-adapter.interface :as modulr-adapter]
    [com.repldriven.queenswood.modulr-simulator.interface :as
     modulr-simulator]
    [com.repldriven.queenswood.onfido-adapter.interface :as onfido-adapter]
    [com.repldriven.queenswood.onfido-simulator.interface :as
     onfido-simulator]
    [com.repldriven.queenswood.uk-companies-house-simulator.interface :as
     ukch-simulator]
    [com.repldriven.queenswood.zyphe-adapter.interface :as zyphe-adapter]
    [com.repldriven.queenswood.zyphe-simulator.interface :as zyphe-simulator]

    [com.repldriven.mono.cli.interface :as cli]
    [com.repldriven.mono.env.interface :as env]
    [com.repldriven.mono.error.interface :as error :refer [nom->]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.system.interface :as system])
  (:gen-class))

(def ^:private handlers
  {:server api/app
   :clearbank-simulator-server clearbank-simulator/app
   :clearbank-adapter-server clearbank-adapter/app
   :form3-simulator-server form3-simulator/app
   :form3-adapter-server form3-adapter/app
   :modulr-simulator-server modulr-simulator/app
   :modulr-adapter-server modulr-adapter/app
   :onfido-simulator-server onfido-simulator/app
   :onfido-adapter-server onfido-adapter/app
   :uk-companies-house-simulator-server ukch-simulator/app
   :zyphe-simulator-server zyphe-simulator/app
   :zyphe-adapter-server zyphe-adapter/app})

(defn- with-handlers
  "Fills the handler of each server the configuration declares, so a
  configuration offering fewer providers starts without the others."
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

(defn stop [system] (system/stop system))

(defn -main
  [& args]
  (log/info args)
  (let [{:keys [options exit-message ok?]} (cli/validate-args "bank-monolith"
                                                              args)]
    (if exit-message
      (cli/exit ok? exit-message)
      (let [{:keys [config-file profile]} options
            sys (start config-file (keyword profile))]
        (if (error/anomaly? sys)
          (cli/exit false sys)
          (do (log/info "System started successfully")
              (when-let [inbox (system/instance sys [:smtp :container-api-url])]
                (log/info "Mail catcher inbox:" inbox))
              @(promise)))))))
