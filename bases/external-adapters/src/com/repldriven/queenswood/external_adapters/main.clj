(ns com.repldriven.queenswood.external-adapters.main
  (:require
    [com.repldriven.queenswood.external-adapters.system]

    [com.repldriven.queenswood.clearbank-adapter.interface :as
     clearbank-adapter]
    [com.repldriven.queenswood.clearbank-simulator.interface :as
     clearbank-simulator]
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

(defn start
  [config-file profile]
  (nom-> (env/config config-file profile)
         system/defs
         (assoc-in [:system/defs :clearbank-simulator-server
                    :handler]
          clearbank-simulator/app)
         (assoc-in [:system/defs :clearbank-adapter-server
                    :handler]
          clearbank-adapter/app)
         (assoc-in [:system/defs :uk-companies-house-simulator-server
                    :handler]
          ukch-simulator/app)
         (assoc-in [:system/defs :zyphe-simulator-server
                    :handler]
          zyphe-simulator/app)
         (assoc-in [:system/defs :zyphe-adapter-server
                    :handler]
          zyphe-adapter/app)
         system/start))

(defn -main
  [& args]
  (log/info args)
  (let [{:keys [options exit-message ok?]}
        (cli/validate-args "bank-external-adapters" args)]
    (if exit-message
      (cli/exit ok? exit-message)
      (let [{:keys [config-file profile]} options
            sys (start config-file (keyword profile))]
        (if (error/anomaly? sys)
          (cli/exit false sys)
          (do (log/info "System started successfully") @(promise)))))))
