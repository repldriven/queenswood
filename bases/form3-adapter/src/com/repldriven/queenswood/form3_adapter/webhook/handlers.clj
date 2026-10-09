(ns com.repldriven.queenswood.form3-adapter.webhook.handlers
  "Form3's notifications carry no signature, so none is trusted: each
  names a resource, which is read back from Form3 with a signed call
  before anything is recorded, and one Form3 does not hold is refused."
  (:require
    [com.repldriven.queenswood.form3-adapter.publisher :as publisher]

    [com.repldriven.queenswood.form3-relay.interface :as relay]
    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.command.interface :as command]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private admission-timeout-ms
  "How long `payment` may take to decide an admission, inside the time
  Form3 gives the bank to complete the task."
  4000)

(defn- fdb
  [request]
  (select-keys request [:record-db :record-store]))

(defn- form3
  [request]
  (select-keys request [:form3-url :credentials]))

(defn- related
  [resource k]
  (get-in resource [:relationships k :data 0 :id]))

(defn- read-back
  "The resource at `path` as Form3 holds it, or an anomaly: a rejection
  where Form3 holds none, a failure where it could not be asked."
  [request path]
  (let [[outcome result] (relay/classify (relay/request (form3 request)
                                                        {:method :get
                                                         :path path}))]
    (case outcome
      :ok (:data result)
      :refused (error/reject
                :payment-webhook/unknown-resource
                {:message
                 "The notification names a resource Form3 does not hold"
                 :path path
                 :reason result})
      (error/fail :payment/unavailable
                  {:message "Form3 could not be asked about the notification"
                   :path path
                   :reason result}))))

(defn- payment-path [payment-id] (str "/v1/transaction/payments/" payment-id))

(defn- persist-one
  [request {:keys [event-name dedup-key data]} settles]
  (let [schema (get (:avro request) event-name)]
    (let-nom> [payload (avro/serialize schema data)]
      (let [res (relay/save-event (fdb request)
                                  (utility/assoc-some
                                   {:outbox-id (str (utility/uuidv7))
                                    :dedup-key dedup-key
                                    :event-name event-name
                                    :payload payload
                                    :correlation-id (str (utility/uuidv7))
                                    :causation-id (str (utility/uuidv7))
                                    :created-at (utility/now)}
                                   :ordering-key
                                   (intent-poller/ordering-key data))
                                  settles)]
        (if (relay/uniqueness-violation? res) :ok res)))))

(defn- respond
  "200 once every event is recorded, or with nothing to record; 400 with
  nothing written where the notification could not be mapped or names
  nothing Form3 holds; 500 otherwise, so Form3 delivers it again."
  ([request what descriptors] (respond request what descriptors nil))
  ([request what descriptors settles]
   (cond
    (error/rejection? descriptors)
    (let [{:keys [message] :as payload} (error/payload descriptors)]
      (log/error (str "Refused " what " notification") payload)
      {:status 400
       :body {:type (str (error/kind descriptors))
              :title "REJECTED"
              :status 400
              :detail message}})

    (error/anomaly? descriptors)
    (do (log/error (str "Failed to read " what " notification") descriptors)
        {:status 500 :body {:error "notification not recorded"}})

    :else
    (let [result (reduce (fn [_ d]
                           (let [res (persist-one request d settles)]
                             (if (error/anomaly? res) (reduced res) :ok)))
                         :ok
                         descriptors)]
      (if (error/anomaly? result)
        (do (log/error (str "Failed to record " what " notification") result)
            {:status 500 :body {:error "notification not recorded"}})
        {:status 200 :body {}})))))

(defn- ours?
  "True for a payment the adapter submitted for the platform."
  [request end-to-end-id]
  (let [intent (relay/find-intent (fdb request) end-to-end-id)]
    (and (not (error/anomaly? intent))
         (= :form3-outbound-intent-kind-payment (:kind intent)))))

(defn- submission
  [request data]
  (let [payment-id (related data :payment)]
    (let-nom> [submission (read-back request
                                     (str (payment-path payment-id)
                                          "/submissions/"
                                          (:id data)))
               payment (read-back request (payment-path payment-id))]
      (let [e2e (get-in payment [:attributes :end_to_end_reference])]
        (if (ours? request e2e)
          (respond request
                   "submission"
                   (publisher/submission payment submission (utility/now))
                   e2e)
          {:status 200 :body {}})))))

(defn- send-admission
  [request data]
  (let [{:keys [avro dispatcher]} request
        id (str (utility/uuidv7))]
    (let-nom> [payload (avro/serialize (get avro "admit-inbound-payment") data)
               res (command/send dispatcher
                                 {:command "admit-inbound-payment"
                                  :id id
                                  :correlation-id id
                                  :payload payload}
                                 {:timeout-ms admission-timeout-ms})]
      (if (= "ACCEPTED" (:status res))
        (avro/deserialize-same (get avro "admit-inbound-payment-reply")
                               (:payload res))
        (error/fail :payment/admission
                    {:message "The platform did not decide the admission"
                     :response (dissoc res :payload)})))))

(defn- decide
  [request data]
  (if-let [admit-fn (:admit-fn request)]
    (admit-fn data)
    (send-admission request data)))

(defn- complete-task
  [request task payment-id admission-id {:keys [admitted reason-code]}]
  (let [[outcome result]
        (relay/classify
         (relay/request
          (form3 request)
          {:method :patch
           :path (str (payment-path payment-id)
                      "/admissions/"
                      admission-id
                      "/tasks/"
                      (:id task))
           :body {:data {:id (:id task)
                         :type "payment_admission_tasks"
                         :version (:version task)
                         :attributes
                         {:status "completed"
                          :output (if admitted
                                    {:outcome "passed"}
                                    {:outcome "failed"
                                     :status_reason (relay/admission-reason
                                                     reason-code)})}}}}))]
    (case outcome
      (:ok :exists) {:status 200 :body {}}
      :refused (do (log/error "Form3 refused the admission task's completion"
                              {:task-id (:id task) :reason result})
                   {:status 200 :body {}})
      (do (log/warn "Form3 admission task not completed; asking again"
                    {:task-id (:id task) :reason result})
          {:status 500 :body {:error "admission not completed"}}))))

(defn- admission-task
  [request data]
  (let [payment-id (related data :payment)
        admission-id (related data :payment_admission)
        res (let-nom> [task (read-back request
                                       (str (payment-path payment-id)
                                            "/admissions/"
                                            admission-id
                                            "/tasks/"
                                            (:id data)))
                       payment (read-back request (payment-path payment-id))]
              (let [{:keys [assignee status]} (:attributes task)]
                (if (and (= "customer" assignee) (= "pending" status))
                  (let-nom> [ask (publisher/admission-request payment)
                             decision (decide request ask)]
                    (log/info "Form3 admission decided"
                              {:payment-id payment-id
                               :admitted (:admitted decision)
                               :reason-code (:reason-code decision)})
                    (complete-task request
                                   task
                                   payment-id
                                   admission-id
                                   decision))
                  {:status 200 :body {}})))]
    (if (error/anomaly? res) (respond request "admission task" res) res)))

(defn- admission
  [request data]
  (let [payment-id (related data :payment)]
    (let-nom> [admission (read-back request
                                    (str (payment-path payment-id)
                                         "/admissions/"
                                         (:id data)))
               payment (read-back request (payment-path payment-id))]
      (respond request
               "admission"
               (publisher/admitted payment admission (utility/now))))))

(defn- return-admission
  [request data]
  (let [payment-id (related data :payment)
        return-id (related data :return)]
    (let-nom> [_ (read-back request
                            (str (payment-path payment-id)
                                 "/returns/"
                                 return-id
                                 "/admissions/"
                                 (:id data)))
               ret (read-back request
                              (str (payment-path payment-id)
                                   "/returns/"
                                   return-id))
               payment (read-back request (payment-path payment-id))]
      (let [e2e (get-in payment [:attributes :end_to_end_reference])]
        (if (ours? request e2e)
          (respond request
                   "return"
                   (publisher/returned payment ret (utility/now)))
          (do (log/error "A return names a payment the adapter did not send"
                         {:payment-id payment-id :return-id return-id})
              {:status 200 :body {}}))))))

(def ^:private handlers
  {["payment_submissions" "updated"] submission
   ["payment_admission_tasks" "created"] admission-task
   ["payment_admissions" "updated"] admission
   ["return_admissions" "created"] return-admission})

(defn notification
  [request]
  (let [{:keys [record_type event_type data]} (get-in request
                                                      [:parameters :body])
        handler (get handlers [record_type event_type])]
    (log/info "Form3 notification received"
              {:record-type record_type
               :event-type event_type
               :id (:id data)})
    (if handler
      (let [res (handler request data)]
        (if (error/anomaly? res) (respond request record_type res) res))
      {:status 200 :body {}})))
