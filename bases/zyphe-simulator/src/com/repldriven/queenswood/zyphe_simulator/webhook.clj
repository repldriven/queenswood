(ns com.repldriven.queenswood.zyphe-simulator.webhook
  "Delivers a settled run's V2 event to the session webhook its
  verification request installed, signed with the secret that request
  supplied, the way Zyphe signs every delivery: `X-Signature` carries
  `t=<seconds>,v0=<hex HMAC-SHA256 of <t>.<body>>`. A run created with
  no session webhook has nowhere to deliver to, since organisation
  endpoints are configured in Zyphe's dashboard rather than its API."
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

(def ^:private flow-status->event-type
  {"COMPLETED" "flow.completed"
   "FAILED" "verification.dv.failed"
   "REJECTED" "verification.dv.failed"
   "CANCELLED" "verification.dv.failed"
   "REVIEW" "verification.dv.review"})

(defn event
  "The V2 envelope Zyphe delivers when the run `vr` reaches
  `flow-status`."
  [vr flow-status]
  (let [{:keys [id organizationId flowId flowResultId identityId
                customData]}
        vr]
    (cond-> {:id (str (utility/uuidv7))
             :type (get flow-status->event-type flow-status)
             :apiVersion api-version
             :createdAt (utility/now-rfc3339)
             :recipientOrganizationId organizationId
             :source {:organizationId organizationId
                      :flowId flowId
                      :flowResultId flowResultId}
             :flow {:status flow-status
                    :slug "onboarding"
                    :customData customData
                    :nextStep nil}}
            (= "COMPLETED" flow-status)
            (assoc :data {:identityId identityId})

            (not= "COMPLETED" flow-status)
            (assoc :data
                   {:dv {:id (str (utility/uuidv7))
                         :verificationRequestId id
                         :flowId flowId
                         :status (if (= "REVIEW" flow-status)
                                   "REVIEW"
                                   "FAILED")
                         :reasons []}}))))

(defn post-event
  "POSTs the event for `vr` reaching `flow-status` to the run's session
  webhook, signed. Returns the response, or nil when the run installed
  no webhook."
  [vr webhook flow-status]
  (if-let [{:keys [url secret]} webhook]
    (let [body (.getBytes ^String (json/write-str (event vr flow-status))
                          StandardCharsets/UTF_8)
          signature (zyphe-webhook/sign secret (quot (utility/now) 1000) body)
          res (http/request {:method :post
                             :url url
                             :headers {"Content-Type" "application/json"
                                       "X-Signature" signature}
                             :body body})]
      (when (or (error/anomaly? res)
                (and (:status res) (>= (:status res) 300)))
        (log/error "Zyphe webhook delivery failed to" url ":" res))
      res)
    (log/warn "Zyphe run has no session webhook; nothing delivered"
              {:verification-request-id (:id vr)})))
