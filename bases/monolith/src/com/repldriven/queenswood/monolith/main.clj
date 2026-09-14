(ns com.repldriven.queenswood.monolith.main
  (:require
    [com.repldriven.queenswood.monolith.system]

    [com.repldriven.queenswood.api.api :as api]
    [com.repldriven.queenswood.clearbank-adapter.interface :as
     clearbank-adapter]
    [com.repldriven.queenswood.clearbank-simulator.interface :as
     clearbank-simulator]
    [com.repldriven.queenswood.onfido-adapter.interface :as onfido-adapter]
    [com.repldriven.queenswood.onfido-simulator.interface :as onfido-simulator]
    [com.repldriven.queenswood.uk-companies-house-simulator.interface :as
     ukch-simulator]

    [com.repldriven.mono.cli.interface :as cli]
    [com.repldriven.mono.env.interface :as env]
    [com.repldriven.mono.error.interface :as error :refer [nom->]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.system.interface :as system])
  (:gen-class))

(defn start
  [config-file profile]
  (nom-> (env/config config-file profile)
         system/defs
         (assoc-in [:system/defs :server :handler] api/app)
         (assoc-in [:system/defs :clearbank-simulator-server
                    :handler]
          clearbank-simulator/app)
         (assoc-in [:system/defs :clearbank-adapter-server
                    :handler]
          clearbank-adapter/app)
         (assoc-in [:system/defs :onfido-simulator-server
                    :handler]
          onfido-simulator/app)
         (assoc-in [:system/defs :onfido-adapter-server
                    :handler]
          onfido-adapter/app)
         (assoc-in [:system/defs :uk-companies-house-simulator-server
                    :handler]
          ukch-simulator/app)
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
