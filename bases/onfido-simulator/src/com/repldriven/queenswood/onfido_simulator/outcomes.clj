(ns com.repldriven.queenswood.onfido-simulator.outcomes
  "What a workflow run produces when the person behind it reaches an
  outcome: a check on the run's applicant holding the reports the
  workflow runs, each with the result the outcome gives it, and the
  status the run ends on. The document report carries what the person
  said their document says, unless the outcome is someone else's
  document. Settling a run records them, marks the run finished and
  delivers `workflow_run.completed`."
  (:require
    [com.repldriven.queenswood.onfido-simulator.webhook :as webhook]

    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :refer [now-rfc3339 uuidv7]]))

(def ^:private someone-else
  {:givenNames "Trillian" :familyName "Astra" :dateOfBirth "1971-05-23"})

(def ^:private clear-aml
  {:sanction "clear" :politically_exposed_person "clear"})

(def ^:private outcomes
  "Per outcome: the document report's result and sub-result, the facial
  similarity, proof-of-address and AML results, and the run's status. A
  report with no result is not produced."
  {"match" {:document ["clear" "clear"]
            :motion "clear"
            :poa "clear"
            :aml clear-aml
            :run "approved"}
   "other-document" {:document ["clear" "clear"]
                     :someone-else true
                     :motion "clear"
                     :poa "clear"
                     :aml clear-aml
                     :run "approved"}
   "document-review" {:document ["consider" "caution"] :run "review"}
   "document-failed" {:document ["consider" "suspected"] :run "declined"}
   "liveness-failed"
   {:document ["clear" "clear"] :motion "consider" :run "declined"}
   "address-failed"
   {:document ["clear" "clear"] :motion "clear" :poa "consider" :run "declined"}
   "sanctions-hit" {:document ["clear" "clear"]
                    :motion "clear"
                    :poa "clear"
                    :aml {:sanction "consider"
                          :politically_exposed_person "clear"}
                    :run "declined"}
   "sanctions-possible-match" {:document ["clear" "clear"]
                               :motion "clear"
                               :poa "clear"
                               :aml {:sanction "consider"
                                     :politically_exposed_person "clear"}
                               :run "review"}
   "pep" {:document ["clear" "clear"]
          :motion "clear"
          :poa "clear"
          :aml {:sanction "clear" :politically_exposed_person "consider"}
          :run "review"}
   "walk-away" {:run "abandoned"}})

(defn known?
  [outcome]
  (contains? outcomes outcome))

(defn- report
  [check-id name result extra]
  (merge {:id (str (uuidv7))
          :name name
          :status "complete"
          :result result
          :check_id check-id
          :created_at (now-rfc3339)}
         extra))

(defn- reports
  [check-id outcome {:keys [givenNames familyName dateOfBirth]}]
  (let [{:keys [document motion poa aml]} outcome
        [result sub-result] document
        said (if (:someone-else outcome)
               someone-else
               {:givenNames givenNames
                :familyName familyName
                :dateOfBirth dateOfBirth})]
    (cond-> []
            document
            (conj (report check-id
                          "document"
                          result
                          {:sub_result sub-result
                           :properties {:first_name (:givenNames said)
                                        :last_name (:familyName said)
                                        :date_of_birth (:dateOfBirth said)
                                        :document_type "passport"
                                        :issuing_country "GBR"}}))

            motion
            (conj (report check-id "facial_similarity_motion" motion nil))

            poa
            (conj (report check-id
                          "proof_of_address"
                          poa
                          {:properties {:document_type "utility_bill"}}))

            aml
            (conj (report check-id
                          "watchlist_aml"
                          (if (every? #{"clear"} (vals aml)) "clear" "consider")
                          {:breakdown {:sanction {:result (:sanction aml)}
                                       :politically_exposed_person
                                       {:result (:politically_exposed_person
                                                 aml)}}})))))

(defn settle
  "Settles the waiting run `id` as a person reaching `outcome` would,
  `document` being what they said their document says, and delivers
  `workflow_run.completed`. Returns the run, `:not-waiting` for a run
  already finished, or nil when there is no such run."
  [state webhook-delay-ms id outcome document]
  (when-let [run (get-in @state [:workflow-runs id])]
    (if-not (= "awaiting_input" (:status run))
      :not-waiting
      (let [spec (get outcomes outcome)
            check-id (str (uuidv7))
            rs (reports check-id spec document)
            now (now-rfc3339)
            finished (assoc run :status (:run spec) :updated_at now)]
        (swap! state
          (fn [s]
            (cond-> (assoc-in s [:workflow-runs id] finished)
                    (seq rs)
                    (-> (assoc-in [:checks check-id]
                                  {:id check-id
                                   :applicant_id (:applicant_id run)
                                   :status "complete"
                                   :result (if (every? (comp #{"clear"} :result)
                                                       rs)
                                             "clear"
                                             "consider")
                                   :report_ids (mapv :id rs)
                                   :created_at now})
                        (update :reports
                                (fn [m]
                                  (into (or m {})
                                        (map (fn [r] [(:id r) r]))
                                        rs)))))))
        (log/info "Onfido simulator settled run"
                  {:workflow-run-id id :outcome outcome})
        (when (pos? (or webhook-delay-ms 0))
          (Thread/sleep (long webhook-delay-ms)))
        (webhook/send-event state
                            "workflow_run"
                            "workflow_run.completed"
                            {:id id
                             :status (:status finished)
                             :completed_at_iso8601 now
                             :href (str "/v3.6/workflow_runs/" id)})
        finished))))
