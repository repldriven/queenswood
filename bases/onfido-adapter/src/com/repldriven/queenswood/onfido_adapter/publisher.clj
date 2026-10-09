(ns com.repldriven.queenswood.onfido-adapter.publisher
  "Maps a finished Onfido workflow run, read back with its reports, to an
  `idv-evidence-received` bus-event descriptor `{:event-name :dedup-key :data}`.
  The document report reports the document, its extracted name graded
  against the applicant's and then left behind, the facial similarity
  report liveness, the proof-of-address report the
  address, and the AML watchlist report sanctions and PEP together; a
  run the person abandoned reports that. A sanctions match on a run
  Onfido declined is a hit, and one it left for review a possible
  match. The run's tags carry the bank and verification ids. The
  webhook handler persists the descriptor to the outbox; the relay
  publishes it."
  (:require
    [com.repldriven.queenswood.idv-provider.interface :as idv-provider]))

(def ^:private document-sub-result->outcome
  {"clear" :idv-evidence-outcome-passed
   "caution" :idv-evidence-outcome-review
   "suspected" :idv-evidence-outcome-failed
   "rejected" :idv-evidence-outcome-failed})

(def ^:private result->outcome
  {"clear" :idv-evidence-outcome-passed
   "consider" :idv-evidence-outcome-failed})

(defn- document-evidence
  [{:keys [result sub_result properties]} run-name]
  (when-let [outcome (or (document-sub-result->outcome sub_result)
                         (result->outcome result))]
    {:document {:outcome outcome
                :name-match (idv-provider/name-match
                             run-name
                             (idv-provider/full-name (:first_name properties)
                                                     (:last_name properties)))
                :document-type (:document_type properties)
                :issuing-country (:issuing_country properties)}}))

(defn- liveness-evidence
  [{:keys [result]}]
  (when-let [outcome (result->outcome result)]
    {:liveness {:outcome outcome}}))

(defn- address-evidence
  [{:keys [result properties]}]
  (when-let [outcome (result->outcome result)]
    {:address {:outcome outcome :document-type (:document_type properties)}}))

(defn- sanctions-outcome
  [run-status breakdown]
  (cond
   (not= "consider" (get-in breakdown [:sanction :result]))
   :idv-sanctions-outcome-clear

   (= "declined" run-status)
   :idv-sanctions-outcome-hit

   :else
   :idv-sanctions-outcome-possible-match))

(defn- screening-evidence
  [run-status {:keys [breakdown]}]
  {:screening {:sanctions (sanctions-outcome run-status breakdown)
               :pep (= "consider"
                       (get-in breakdown
                               [:politically_exposed_person :result]))}})

(defn- report-evidence
  [run-status run-name {:keys [name status] :as report}]
  (when (= "complete" status)
    (case name
      "document" (document-evidence report run-name)
      "facial_similarity_motion" (liveness-evidence report)
      "proof_of_address" (address-evidence report)
      "watchlist_aml" (screening-evidence run-status report)
      nil)))

(defn- evidence
  [{:keys [status]} run-name reports]
  (if (= "abandoned" status)
    {:cancelled true}
    (not-empty (apply merge
                      (keep (fn [r] (report-evidence status run-name r))
                            reports)))))

(defn ->idv-evidence
  "The idv-evidence event descriptor for a finished run read back with
  `read-run` and `:run-name`, the name of the party the run is for, or
  nil when it reports nothing a decision rests on or its tags name no
  verification. `dedup-key` is the run's completion, which
  Onfido's retries repeat."
  [{:keys [run run-name bank-id verification-id reports]}]
  (let [reported (evidence run run-name reports)]
    (when (and reported bank-id verification-id)
      {:event-name "idv-evidence-received"
       :dedup-key (str (:id run) ":completed")
       :data (merge {:bank-id bank-id
                     :verification-id verification-id
                     :cancelled false}
                    reported)})))
