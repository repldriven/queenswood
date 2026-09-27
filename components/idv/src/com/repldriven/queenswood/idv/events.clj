(ns com.repldriven.queenswood.idv.events
  (:require
    [com.repldriven.queenswood.idv.core :as core]
    [com.repldriven.queenswood.idv.domain :as domain]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.processor.interface :as processor]))

(defn- ->updated
  [idv status]
  (case status
    "IN_REVIEW" (domain/in-review-idv idv)
    "ACCEPTED" (domain/accepted-idv idv)
    "REJECTED" (domain/rejected-idv idv)
    "FAILED" (domain/failed-idv idv)
    nil))

(defn- handle-idv-completed
  [config data]
  (let [{:keys [record-db record-store]} config
        bank {:record-db record-db :record-store record-store}
        {:keys [bank-id verification-id status]} data
        idv (core/get-idv bank bank-id verification-id)]
    (cond
     (error/anomaly? idv)
     (do (log/error "Failed to load IDV for idv-completed event"
                    {:verification-id verification-id :anomaly idv})
         nil)

     :else
     (let [updated (->updated idv status)]
       (cond
        (nil? updated)
        (do (log/warnf "Unknown idv-completed status: %s" status)
            nil)

        (error/anomaly? updated)
        (do
          (log/info
           "Skipping idv-completed event — IDV not in a status that accepts this transition"
           {:verification-id verification-id
            :status (:status idv)
            :incoming-status status})
          nil)

        :else
        (let-nom>
          [_ (core/save-idv bank
                            updated
                            {:bank-id bank-id
                             :verification-id verification-id
                             :status-before (:status idv)
                             :status-after (:status updated)})]
          updated))))))

(defn- dispatch
  [config message]
  (let [{:keys [event payload]} message
        {:keys [schemas]} config
        schema (get schemas event)]
    (if-not schema
      (do (log/warnf "Unknown IDV event: %s" event) nil)
      (let-nom> [data (avro/deserialize-same schema payload)]
        (case event
          "idv-completed" (handle-idv-completed config data)
          "idv-evidence" (core/apply-evidence config data)
          "idv-session-opened" (core/record-hand-off config data)
          (do (log/warnf "Unknown IDV event: %s" event) nil))))))

(defrecord IdvEventProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))

(defn- handle-party-status-changed
  [config data]
  (let [{:keys [bank-id party-id status-after]} data]
    (when (= :party-status-pending status-after)
      (core/initiate-for-party config bank-id party-id))))

(defn- dispatch-party
  [config message]
  (let [{:keys [event payload]} message
        {:keys [schemas]} config
        schema (get schemas event)]
    (if-not schema
      (do (log/warnf "Unknown parties event: %s" event) nil)
      (let-nom> [data (avro/deserialize-same schema payload)]
        (case event
          "party-status-changed" (handle-party-status-changed config data)
          (do (log/warnf "Unknown parties event: %s" event) nil))))))

(defrecord IdvPartyEventProcessor [config]
  processor/Processor
    (process [_ message] (dispatch-party config message)))
