(ns com.repldriven.queenswood.idv.commands
  (:require
    [com.repldriven.queenswood.idv.core :as core]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.processor.interface :as processor]))

(defn- idv->avro
  [idv]
  (-> idv
      (dissoc :evidence :criteria)
      (update :accepted-at (fn [t] (when (and t (pos? t)) t)))
      (update :rejected-at (fn [t] (when (and t (pos? t)) t)))
      (update :cancelled-at (fn [t] (when (and t (pos? t)) t)))))

(defn- ->response
  [config result]
  (if (error/anomaly? result)
    result
    (let [{:keys [schemas]} config]
      {:status "ACCEPTED"
       :payload (avro/serialize (schemas "idv") (idv->avro result))})))

(def ^:private session-keys
  [:bank-id :session-id :verification-id :party-id :channel :return-url
   :status :created-at :updated-at])

(defn- open-idv-session
  [config data]
  (let [result (core/open-session config data)]
    (if (error/anomaly? result)
      result
      (let [{:keys [schemas]} config]
        {:status "ACCEPTED"
         :payload (avro/serialize (schemas "idv-session")
                                  (select-keys result session-keys))}))))

(def ^:private command-handlers
  {"initiate-idv" (fn [config data]
                    (->response config (core/initiate config data)))
   "get-idv" (fn [config data] (->response config (core/get config data)))
   "open-idv-session" open-idv-session})

(defn- dispatch
  [config message]
  (let [{:keys [command payload]} message
        handler (get command-handlers command)]
    (if (nil? handler)
      (error/reject :idv/unknown-command
                    (str "Unknown command: " command))
      (let [{:keys [schemas]} config
            schema (get schemas command)]
        (if-not schema
          (error/fail :idv/process-command
                      {:message "No schema found for command"
                       :command command})
          (let-nom> [data (avro/deserialize-same schema payload)]
            (handler config data)))))))

(defrecord IdvProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))
