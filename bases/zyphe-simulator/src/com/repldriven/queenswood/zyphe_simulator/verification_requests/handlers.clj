(ns com.repldriven.queenswood.zyphe-simulator.verification-requests.handlers
  (:require
    [com.repldriven.queenswood.zyphe-simulator.webhook :as webhook]

    [com.repldriven.queenswood.idv-simulator-page.interface :as page]

    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]))

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
  "Settles the pending run `id` as a person reaching `outcome` would,
  `document` being what they said their document says, and delivers
  its events. Returns the updated verification request, `:not-pending`
  for a run already settled, or nil when there is no such run."
  [state id outcome document]
  (when-let [{:keys [webhook vr]} (get-in @state [:verification-requests id])]
    (if-not (= "PENDING" (:status vr))
      :not-pending
      (let [status (webhook/flow-status->vr-status (webhook/flow-status
                                                    outcome))
            run (get-in (swap! state assoc-in
                          [:verification-requests id :vr :status]
                          status)
                        [:verification-requests id])]
        (log/info "Zyphe simulator settled run"
                  {:verification-request-id id :outcome outcome})
        (webhook/post-events (:vr run) webhook outcome document)
        (:vr run)))))

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
  [{:keys [zid webhook vr token]} sandbox email]
  (cond-> {:verificationRequest (dissoc vr :flowResultId)
           :zid zid
           :zypheToken token
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

(defn create-verification-request
  [_config]
  (fn [request]
    (let [{:keys [state parameters headers api-key]} request
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
             run (-> (if existing
                         (cond-> existing
                                 (:webhook body)
                                 (assoc :webhook (:webhook body)))
                         (new-run request zid body flow-id))
                     (assoc :token (str "simulated-token-" (utility/uuidv7))))
             id (get-in run [:vr :id])]
         (swap! state assoc-in [:verification-requests id] run)
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

(defn- not-found
  [id]
  (baxe-error 404
              undocumented-code
              "verification_request_not_found"
              (str "No verification request with id: " id)))

(defn- ->document
  [body]
  (select-keys body [:givenNames :familyName :dateOfBirth]))

(defn decide
  [_config]
  (fn [request]
    (let [{:keys [state parameters]} request
          id (get-in parameters [:path :id])
          {:keys [body]} parameters
          vr (settle state id (:outcome body) (->document body))]
      (cond
       (nil? vr)
       (not-found id)

       (= :not-pending vr)
       (baxe-error 409
                   undocumented-code
                   "verification_request_not_pending"
                   (str "Verification request already settled: " id))

       :else
       {:status 200 :body (dissoc vr :flowResultId)}))))

(defn hosted-page
  [_config]
  (fn [request]
    (let [{:keys [state parameters]} request
          {:keys [zypheVr zypheToken zypheHandoffBaseUrl]} (:query parameters)
          run (get-in @state [:verification-requests zypheVr])]
      (cond
       (nil? run)
       (page/response 404 (page/message "No such verification"))

       (not= zypheToken (:token run))
       (page/response 401 (page/message "This link is no longer valid"))

       (not= "PENDING" (get-in run [:vr :status]))
       (page/response 409 (page/message "This verification is finished"))

       :else
       (page/response 200
                      (page/form "/(sandbox/)?flow/[^/]*$"
                                 (str "/simulator/verification-requests/"
                                      zypheVr
                                      "/decision")
                                 zypheHandoffBaseUrl))))))
