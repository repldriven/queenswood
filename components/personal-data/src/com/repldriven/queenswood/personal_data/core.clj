(ns com.repldriven.queenswood.personal-data.core
  (:require
    [com.repldriven.queenswood.idv.interface :as idv]
    [com.repldriven.queenswood.onfido-relay.interface :as onfido-relay]
    [com.repldriven.queenswood.party.interface :as party]
    [com.repldriven.queenswood.person-identification.interface :as
     person-identification]
    [com.repldriven.queenswood.zyphe-relay.interface :as zyphe-relay]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]))

(defn clear
  [config]
  (let-nom> [identifications (person-identification/clear-identity-details
                              config)
             evidence (idv/clear-read-evidence config)
             identifiers (party/delete-national-identifiers config)
             zyphe (zyphe-relay/clear-personal-data config)
             onfido (onfido-relay/clear-personal-data config)]
    (let [cleared {:person-identifications identifications
                   :idv-evidence evidence
                   :national-identifiers identifiers
                   :intents (+ (:intents zyphe) (:intents onfido))
                   :payloads (+ (:payloads zyphe) (:payloads onfido))}]
      (log/info "Personal data cleared" cleared)
      cleared)))
