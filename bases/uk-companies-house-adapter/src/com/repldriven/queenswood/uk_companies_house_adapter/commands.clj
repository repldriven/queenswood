(ns com.repldriven.queenswood.uk-companies-house-adapter.commands
  (:require
    [com.repldriven.queenswood.uk-companies-house-adapter.companies-house :as
     companies-house]

    [com.repldriven.queenswood.company.interface :as company]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.processor.interface :as processor]))

(defn- ->response
  [config result]
  (if (error/anomaly? result)
    result
    (let [{:keys [schemas]} config]
      {:status "ACCEPTED"
       :payload (avro/serialize (schemas "company") result)})))

(defn- lookup-company
  "Fetch the company profile from Companies House and cache it in FDB.
  Unlike the Onfido and ClearBank adapters this replies with the record
  rather than persisting an intent: the caller is a live request waiting
  on the command response, so there is nothing to drain later."
  [config data]
  (let [{:keys [record-db record-store]} config
        {:keys [company-number]} data
        result (let-nom>
                 [body (companies-house/fetch-company config company-number)
                  company (companies-house/body->company body)]
                 (company/save-company {:record-db record-db
                                        :record-store record-store}
                                       company))]
    (->response config result)))

(def ^:private command-handlers {"lookup-company" lookup-company})

(defn- dispatch
  [config message]
  (let [{:keys [command payload]} message
        handler (get command-handlers command)]
    (if (nil? handler)
      (error/reject :uk-companies-house-adapter/unknown-command
                    (str "Unknown command: " command))
      (let [{:keys [schemas]} config
            schema (get schemas command)]
        (if-not schema
          (error/fail :uk-companies-house-adapter/process-command
                      {:message "No schema found for command"
                       :command command})
          (let-nom> [data (avro/deserialize-same schema payload)]
            (handler config data)))))))

(defrecord UkCompaniesHouseCommandProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))
