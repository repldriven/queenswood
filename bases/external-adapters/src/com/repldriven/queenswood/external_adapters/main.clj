(ns com.repldriven.queenswood.external-adapters.main
  (:require
    [com.repldriven.queenswood.external-adapters.system]

    [com.repldriven.queenswood.clearbank-adapter.interface :as
     clearbank-adapter]
    [com.repldriven.queenswood.form3-adapter.interface :as form3-adapter]
    [com.repldriven.queenswood.modulr-adapter.interface :as modulr-adapter]
    [com.repldriven.queenswood.onfido-adapter.interface :as onfido-adapter]
    [com.repldriven.queenswood.zyphe-adapter.interface :as zyphe-adapter]

    [com.repldriven.mono.cli.interface :as cli]
    [com.repldriven.mono.env.interface :as env]
    [com.repldriven.mono.error.interface :as error :refer [nom->]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.system.interface :as system])
  (:gen-class))

(def ^:private handlers
  {:clearbank-adapter-server clearbank-adapter/app
   :form3-adapter-server form3-adapter/app
   :modulr-adapter-server modulr-adapter/app
   :onfido-adapter-server onfido-adapter/app
   :zyphe-adapter-server zyphe-adapter/app})

(defn- with-handlers
  "Fills the handler of each server the configuration declares, so the
  configuration alone decides which providers run."
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
        (cli/validate-args "bank-external-adapters" args)]
    (if exit-message
      (cli/exit ok? exit-message)
      (let [{:keys [config-file profile]} options
            sys (start config-file (keyword profile))]
        (if (error/anomaly? sys)
          (cli/exit false sys)
          (do (system/stop-on-shutdown sys)
              (log/info "System started successfully")
              @(promise)))))))
