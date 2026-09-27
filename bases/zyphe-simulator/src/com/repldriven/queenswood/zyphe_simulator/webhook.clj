(ns com.repldriven.queenswood.zyphe-simulator.webhook
  "Delivers a settled run's V2 events to the session webhook its
  verification request installed, signed with the secret that request
  supplied, the way Zyphe signs every delivery: `X-Signature` carries
  `t=<seconds>,v0=<hex HMAC-SHA256 of <t>.<body>>`. An outcome is the
  results a person or a reviewer produces — a document result carrying
  what the document says, a proof-of-address result, an AML result —
  delivered in that order, and the run's completion where every step
  passed. A run created with no session webhook has nowhere to deliver
  to, since organisation endpoints are configured in Zyphe's dashboard
  rather than its API."
  (:require
    [com.repldriven.queenswood.zyphe-webhook.interface :as zyphe-webhook]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility])
  (:import
    (java.nio.charset StandardCharsets)))

(def ^:private api-version "2026-08-26")

(def ^:private someone-else
  "What a document belonging to someone else says."
  {:givenNames "Trillian" :familyName "Astra" :dateOfBirth "1971-05-23"})

(def ^:private clear {:hasSanctions false :hasPep false :status "APPROVED"})

(def ^:private outcomes
  "Per outcome: the document result's status and reasons, whose
  document it is, the proof-of-address status, the AML result, and the
  status the run ends on. A nil result is not delivered."
  {"match" {:dv "PASSED" :poa "PASSED" :aml clear :flow "COMPLETED"}
   "other-document" {:dv "PASSED"
                     :document someone-else
                     :poa "PASSED"
                     :aml clear
                     :flow "COMPLETED"}
   "document-review" {:dv "REVIEW" :flow "REVIEW"}
   "document-failed"
   {:dv "FAILED" :reasons ["FAKE_DOCUMENT_DETECTED"] :flow "FAILED"}
   "liveness-failed"
   {:dv "FAILED" :reasons ["LIVENESS_CHECK_FAILED"] :flow "FAILED"}
   "address-failed" {:dv "PASSED" :poa "FAILED" :flow "FAILED"}
   "sanctions-hit" {:dv "PASSED"
                    :poa "PASSED"
                    :aml {:hasSanctions true :hasPep false :status "REJECTED"}
                    :flow "REJECTED"}
   "sanctions-possible-match"
   {:dv "PASSED"
    :poa "PASSED"
    :aml {:hasSanctions true :hasPep false :status "ESCALATED"}
    :flow "REVIEW"}
   "pep" {:dv "PASSED"
          :poa "PASSED"
          :aml {:hasSanctions false :hasPep true :status "ESCALATED"}
          :flow "REVIEW"}
   "walk-away" {:cancelled true :flow "CANCELLED"}})

(def flow-status->vr-status
  {"COMPLETED" "COMPLETED"
   "FAILED" "FAILED"
   "REJECTED" "REJECTED"
   "CANCELLED" "CANCELLED"
   "REVIEW" "REQUIRES_MANUAL_REVIEW"})

(defn flow-status
  "The status a run ends on for `outcome`."
  [outcome]
  (get-in outcomes [outcome :flow]))

(def ^:private result-status->event
  {"PASSED" "completed" "FAILED" "failed" "REVIEW" "review"})

(defn- envelope
  [vr type flow-status data]
  (let [{:keys [organizationId flowId flowResultId customData]} vr]
    {:id (str (utility/uuidv7))
     :type type
     :apiVersion api-version
     :createdAt (utility/now-rfc3339)
     :recipientOrganizationId organizationId
     :source {:organizationId organizationId
              :flowId flowId
              :flowResultId flowResultId}
     :flow {:status flow-status
            :slug "onboarding"
            :customData customData
            :nextStep nil}
     :data data}))

(defn- result
  [vr status]
  {:id (str (utility/uuidv7))
   :verificationRequestId (:id vr)
   :flowId (:flowId vr)
   :status status
   :customData (:customData vr)})

(defn events
  "The V2 events a real Zyphe delivers when the person behind `vr`
  reaches `outcome`, `document` being what they said their document
  says."
  [vr outcome document]
  (let [{:keys [dv reasons poa aml cancelled flow]} (get outcomes outcome)
        document (or (get-in outcomes [outcome :document]) document)
        interim (if (= "COMPLETED" flow) "PROCESSING" flow)]
    (if cancelled
      [(envelope vr
                 "verification.dv.failed"
                 flow
                 {:dv (assoc (result vr "FAILED") :reasons [])})]
      (cond-> []
              dv
              (conj (envelope vr
                              (str "verification.dv."
                                   (result-status->event dv))
                              interim
                              {:dv (assoc (result vr dv)
                                          :reasons (or reasons [])
                                          :documentType "Passport")
                               :additionalData
                               {:firstName (:givenNames document)
                                :lastName (:familyName document)
                                :dateOfBirth (:dateOfBirth document)
                                :issuingState "GBR"
                                :documentClassCode "P"}}))

              poa
              (conj (envelope vr
                              (str "verification.poa."
                                   (result-status->event poa))
                              interim
                              {:poa (assoc (result vr poa)
                                           :reason (when (= "FAILED" poa)
                                                     "UNRECOGNIZED_ADDRESS")
                                           :documentType "UTILITY_BILL")}))

              aml
              (conj (envelope vr
                              "verification.aml.produced"
                              interim
                              {:aml (merge (result vr (:status aml))
                                           {:updateKind "PRODUCED"
                                            :subjectType "PERSON"
                                            :riskScorePercent 12}
                                           aml)}))

              (= "COMPLETED" flow)
              (conj (envelope vr
                              "flow.completed"
                              flow
                              {:identityId (:identityId vr)}))))))

(defn- post
  [{:keys [url secret]} event]
  (let [body (.getBytes ^String (json/write-str event) StandardCharsets/UTF_8)
        signature (zyphe-webhook/sign secret (quot (utility/now) 1000) body)
        res (http/request {:method :post
                           :url url
                           :headers {"Content-Type" "application/json"
                                     "X-Signature" signature}
                           :body body})]
    (when (or (error/anomaly? res)
              (and (:status res) (>= (:status res) 300)))
      (log/error "Zyphe webhook delivery failed to" url ":" res))
    res))

(defn post-events
  "POSTs the events for `vr` reaching `outcome` to the run's session
  webhook, signed, in order. Returns the responses, or nil when the run
  installed no webhook."
  [vr webhook outcome document]
  (if webhook
    (mapv (fn [event] (post webhook event)) (events vr outcome document))
    (log/warn "Zyphe run has no session webhook; nothing delivered"
              {:verification-request-id (:id vr)})))
