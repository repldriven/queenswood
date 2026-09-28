(ns com.repldriven.queenswood.onfido-simulator.workflow-runs.handlers
  (:require
    [com.repldriven.queenswood.onfido-simulator.outcomes :as outcomes]

    [com.repldriven.queenswood.idv-simulator-page.interface :as page]

    [com.repldriven.mono.utility.interface :refer [now-rfc3339 uuidv7]]

    [clojure.string :as str]))

(defn- error
  [status type detail]
  {:status status
   :body {:title (str/upper-case (str/replace type #"^.*/" ""))
          :type type
          :status status
          :detail detail}})

(defn- not-found
  [id]
  (error 404 "workflow_run/not-found" (str "No workflow run with id: " id)))

(defn- origin
  "The root URL the request reached the simulator at, which the person
  reaches it at too."
  [{:keys [scheme headers]}]
  (str (name (or scheme :http)) "://" (get headers "host")))

(defn create-workflow-run
  [_config]
  (fn [request]
    (let [{:keys [state parameters]} request
          {:keys [workflow_id applicant_id tags customer_user_id link]}
          (:body parameters)
          id (str (uuidv7))
          now (now-rfc3339)]
      (if-not (get-in @state [:applicants applicant_id])
        (error 422
               "applicant/not-found"
               (str "No applicant with id: " applicant_id))
        (let [run {:id id
                   :workflow_id workflow_id
                   :workflow_version_id 1
                   :applicant_id applicant_id
                   :customer_user_id customer_user_id
                   :tags (vec tags)
                   :status "awaiting_input"
                   :link (assoc link :url (str (origin request) "/l/" id))
                   :created_at now
                   :updated_at now}]
          (swap! state assoc-in [:workflow-runs id] run)
          {:status 201 :body run})))))

(defn list-workflow-runs
  [_config]
  (fn [request]
    (let [{:keys [state parameters]} request
          {:keys [tags applicant_id]} (:query parameters)
          wanted (when tags (set (str/split tags #",")))]
      {:status 200
       :body (->> (vals (:workflow-runs @state))
                  (filter (fn [run]
                            (and (or (nil? wanted)
                                     (every? (set (:tags run)) wanted))
                                 (or (nil? applicant_id)
                                     (= applicant_id (:applicant_id run))))))
                  (sort-by :created_at)
                  vec)})))

(defn get-workflow-run
  [_config]
  (fn [request]
    (let [{:keys [state parameters]} request
          id (get-in parameters [:path :id])]
      (if-let [run (get-in @state [:workflow-runs id])]
        {:status 200 :body run}
        (not-found id)))))

(defn hosted-page
  [_config]
  (fn [request]
    (let [{:keys [state parameters]} request
          id (get-in parameters [:path :id])
          run (get-in @state [:workflow-runs id])]
      (cond
       (nil? run)
       (page/response 404 (page/message "No such verification"))

       (not= "awaiting_input" (:status run))
       (page/response 409 (page/message "This verification is finished"))

       :else
       (page/response 200
                      (page/form "/l/[^/]*$"
                                 (str "/simulator/workflow-runs/"
                                      id
                                      "/decision")
                                 (get-in run
                                         [:link :completed_redirect_url])))))))

(defn decide
  [_config]
  (fn [request]
    (let [{:keys [state parameters webhook-delay-ms]} request
          id (get-in parameters [:path :id])
          {:keys [outcome] :as body} (:body parameters)
          run (when (outcomes/known? outcome)
                (outcomes/settle state
                                 webhook-delay-ms
                                 id
                                 outcome
                                 (select-keys body
                                              [:givenNames :familyName
                                               :dateOfBirth])))]
      (cond
       (not (outcomes/known? outcome))
       (error 422 "workflow_run/unknown-outcome" (str "No outcome: " outcome))

       (nil? run)
       (not-found id)

       (= :not-waiting run)
       (error 409
              "workflow_run/finished"
              (str "Workflow run already finished: " id))

       :else
       {:status 200 :body run}))))
