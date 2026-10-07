(ns com.repldriven.queenswood.zyphe-adapter.publisher
  "Maps a verified Zyphe V2 webhook event to an `idv-evidence` bus-event
  descriptor `{:event-name :dedup-key :data}`. A document result reports
  the document and, since Zyphe reports liveness failures as its
  reasons, liveness; a proof-of-address result reports the address; an
  AML result reports sanctions and PEP together; and a run the person
  walked away from reports that. The custom data the relay attached
  when it created the run carries the bank, verification and party ids;
  the document's read name is graded against the party's, and the read
  name, date of birth and every other field the person gave Zyphe stay
  here. The webhook handler persists the
  descriptor to the outbox; the relay publishes it."
  (:require
    [com.repldriven.queenswood.idv-provider.interface :as idv-provider]

    [clojure.string :as str]))

(def ^:private result-status->outcome
  {"PASSED" :idv-evidence-outcome-passed
   "FAILED" :idv-evidence-outcome-failed
   "PARTIAL" :idv-evidence-outcome-failed
   "REVIEW" :idv-evidence-outcome-review})

(defn- liveness-reason?
  [reason]
  (or (str/starts-with? reason "LIVENESS_")
      (str/starts-with? reason "FACE_MATCH")))

(defn- document-evidence
  [dv additional run-name]
  (let [{:keys [status reasons documentType]} dv
        outcome (result-status->outcome status)
        liveness-failed? (and (= :idv-evidence-outcome-failed outcome)
                              (some liveness-reason? reasons))]
    (when outcome
      (cond-> {}
              (not liveness-failed?)
              (assoc :document
                     {:outcome outcome
                      :name-match (idv-provider/name-match
                                   run-name
                                   (idv-provider/full-name
                                    (:firstName additional)
                                    (:lastName additional)))
                      :document-type (or documentType
                                         (:documentClassCode additional))
                      :issuing-country (:issuingState additional)})

              (= :idv-evidence-outcome-passed outcome)
              (assoc :liveness {:outcome :idv-evidence-outcome-passed})

              liveness-failed?
              (assoc :liveness {:outcome :idv-evidence-outcome-failed})))))

(defn- address-evidence
  [poa]
  (when-let [outcome (result-status->outcome (:status poa))]
    {:address {:outcome outcome :document-type (:documentType poa)}}))

(defn- sanctions-outcome
  [{:keys [hasSanctions status]}]
  (cond
   (not hasSanctions)
   :idv-sanctions-outcome-clear

   (= "APPROVED" status)
   :idv-sanctions-outcome-clear

   (= "REJECTED" status)
   :idv-sanctions-outcome-hit

   :else
   :idv-sanctions-outcome-possible-match))

(defn- screening-evidence
  [aml]
  (when aml
    {:screening {:sanctions (sanctions-outcome aml)
                 :pep (boolean (:hasPep aml))}}))

(defn- evidence
  [event run-name]
  (let [{:keys [type flow data]} event
        {:keys [dv additionalData poa aml]} data]
    (cond
     (= "CANCELLED" (:status flow))
     {:cancelled true}

     (str/starts-with? (or type "") "verification.dv.")
     (document-evidence dv additionalData run-name)

     (str/starts-with? (or type "") "verification.poa.")
     (address-evidence poa)

     (str/starts-with? (or type "") "verification.aml.")
     (screening-evidence aml))))

(defn- ids
  [event]
  (let [{:keys [flow data]} event]
    (or (not-empty (:customData flow))
        (some (fn [[_ result]] (not-empty (:customData result))) data))))

(defn party-id
  "The party the run behind `event` is for, from its custom data."
  [event]
  (:partyId (ids event)))

(defn ->idv-evidence
  "The idv-evidence event descriptor for `event`, or nil when it reports
  nothing a decision rests on — a run's completion, a notification, or
  an event whose custom data names no verification. `dedup-key` is the
  event's id, which Zyphe keeps across retries and endpoints. A document
  is graded against `run-name`, the party's name."
  [event run-name]
  (let [{:keys [bankId verificationId]} (ids event)
        reported (evidence event run-name)]
    (when (and reported bankId verificationId)
      {:event-name "idv-evidence"
       :dedup-key (:id event)
       :data (merge {:bank-id bankId
                     :verification-id verificationId
                     :cancelled false}
                    reported)})))
