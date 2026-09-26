(ns com.repldriven.queenswood.zyphe-simulator.verification-requests.handlers
  (:require
    [com.repldriven.queenswood.zyphe-simulator.webhook :as webhook]

    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private flow-status->vr-status
  {"COMPLETED" "COMPLETED"
   "FAILED" "FAILED"
   "REJECTED" "REJECTED"
   "CANCELLED" "CANCELLED"
   "REVIEW" "REQUIRES_MANUAL_REVIEW"})

(def ^:private undocumented-code 0)

(defn- baxe-error
  [status code error-tag message]
  {:status status :body {:code code :errorTag error-tag :message message}})

(defn- identity-key
  "The identity a request names, as Zyphe keys it: the email, or the
  first credential. Nil when the request names neither."
  [{:keys [email credentials]}]
  (or email
      (some (fn [{:keys [type externalId chain address]}]
              (case type
                "EXTERNAL_ID" (when externalId (str "EXTERNAL_ID:" externalId))
                "WALLET" (when address (str chain ":" address))
                nil))
            credentials)))

(defn- open-run
  "The run for `flow-id` and `zid` still waiting on the person, if any —
  creating again resumes it rather than starting another."
  [state flow-id zid]
  (some (fn [{:keys [vr] :as run}]
          (when (and (= flow-id (:flowId vr))
                     (= zid (:zid run))
                     (= "PENDING" (:status vr)))
            run))
        (vals (:verification-requests @state))))

(defn settle
  "Settles the run `id` at `flow-status` and delivers its event. Returns
  the updated verification request, or nil when there is no such run."
  [state id flow-status]
  (when-let [{:keys [webhook]} (get-in @state [:verification-requests id])]
    (let [run (get-in (swap! state assoc-in
                        [:verification-requests id :vr :status]
                        (get flow-status->vr-status flow-status))
                      [:verification-requests id])]
      (log/info "Zyphe simulator settled run"
                {:verification-request-id id :flow-status flow-status})
      (webhook/post-event (:vr run) webhook flow-status)
      (:vr run))))

(defn- new-run
  [request zid body flow-id]
  (let [{:keys [organization-id]} request
        {:keys [customData webhook]} body]
    {:zid zid
     :webhook webhook
     :vr {:id (str (utility/uuidv7))
          :identityId (str (utility/uuidv7))
          :flowId flow-id
          :flowStepId (str (utility/uuidv7))
          :flowResultId (str (utility/uuidv7))
          :organizationId organization-id
          :customData (or customData {})
          :status "PENDING"
          :attemptsCount 0
          :createdAt (utility/now-rfc3339)}}))

(defn- response
  [{:keys [zid webhook vr]} sandbox email]
  (cond-> {:verificationRequest (dissoc vr :flowResultId)
           :zid zid
           :zypheToken (str "simulated-token-" (:id vr))
           :zypheAccessSig (str "simulated-signature-" (:id vr))
           :flowSlug "onboarding"
           :flowStepSlug "document-verification"
           :isSandbox sandbox}
          email
          (assoc :email email)

          webhook
          (assoc :sessionId (:id vr)
                 :sessionWebhook {:id (str "wh-" (:id vr))
                                  :secretHint (str "…"
                                                   (subs (:secret webhook)
                                                         (- (count
                                                             (:secret webhook))
                                                            4)))
                                  :expiresAt (utility/now-rfc3339)})))

(defn- schedule-settlement
  "Stands in for the person completing the hosted flow: settles the run
  at `outcome` after `delay-ms`, or leaves it pending for a decision
  when no delay is configured."
  [state id delay-ms outcome]
  (when delay-ms
    (future
     (when (pos? delay-ms) (Thread/sleep ^long delay-ms))
     (settle state id (or outcome "COMPLETED")))))

(defn create-verification-request
  [_config]
  (fn [request]
    (let [{:keys [state parameters headers api-key auto-settle-ms
                  auto-outcome]}
          request
          {:keys [path query body]} parameters
          flow-id (:flow_id path)
          given-key (get headers "x-api-key")
          zid (identity-key body)]
      (cond
       (nil? given-key)
       (baxe-error 401 undocumented-code
                   "missing_api_key" "No x-api-key header")

       (and api-key (not= api-key given-key))
       (baxe-error 401 10201 "invalid_api_key" "Invalid API key")

       (nil? zid)
       (baxe-error 400
                   undocumented-code
                   "bad_request"
                   "Provide an email or a credential")

       :else
       (let [existing (open-run state flow-id zid)
             run (if existing
                   (cond-> existing
                           (:webhook body)
                           (assoc :webhook (:webhook body)))
                   (new-run request zid body flow-id))
             id (get-in run [:vr :id])]
         (swap! state assoc-in [:verification-requests id] run)
         (when-not existing
           (schedule-settlement state id auto-settle-ms auto-outcome))
         {:status 200 :body (response run (:sandbox query) (:email body))})))))

(defn get-verification-request
  [_config]
  (fn [request]
    (let [{:keys [state parameters]} request
          id (get-in parameters [:path :id])
          vr (get-in @state [:verification-requests id :vr])]
      (if vr
        {:status 200 :body (dissoc vr :flowResultId)}
        (baxe-error 404
                    undocumented-code
                    "verification_request_not_found"
                    (str "No verification request with id: " id))))))

(defn decide
  [_config]
  (fn [request]
    (let [{:keys [state parameters]} request
          id (get-in parameters [:path :id])
          {:keys [flowStatus]} (:body parameters)
          vr (settle state id flowStatus)]
      (if vr
        {:status 200 :body (dissoc vr :flowResultId)}
        (baxe-error 404
                    undocumented-code
                    "verification_request_not_found"
                    (str "No verification request with id: " id))))))
