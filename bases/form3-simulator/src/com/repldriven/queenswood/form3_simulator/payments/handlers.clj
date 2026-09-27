(ns com.repldriven.queenswood.form3-simulator.payments.handlers
  (:require
    [com.repldriven.queenswood.form3-simulator.responses :as responses]
    [com.repldriven.queenswood.form3-simulator.scheme :as scheme]
    [com.repldriven.queenswood.form3-simulator.signed :as signed]
    [com.repldriven.queenswood.form3-simulator.records :as records]))

(def refused-sort-code
  "A beneficiary under this sort code is refused when submitted, before
  anything reaches the scheme."
  "999998")

(defn- path
  [request k]
  (get-in request [:parameters :path k]))

(defn- data
  [request]
  (get-in request [:parameters :body :data]))

(defn- child
  "The resource of `kind` with id `id` belonging to the path's payment."
  [request kind id]
  (let [r (records/get-in-state (:state request) kind id)]
    (when (= (path request :id) (::records/payment-id r)) r)))

(def create
  (signed/verified
   (fn [request]
     (let [{:keys [state organisation-id]} request
           {:keys [id attributes]} (data request)
           {:keys [amount currency beneficiary_party end_to_end_reference]}
           attributes]
       (cond
        (records/get-in-state state :payments id)
        (signed/api-error 409 "A payment with this id already exists")

        (not (and amount
                  currency
                  end_to_end_reference
                  (:bank_id beneficiary_party)
                  (:account_number beneficiary_party)))
        (signed/api-error 400
                          (str "amount, currency, end_to_end_reference and "
                               "the beneficiary's bank_id and account_number "
                               "are required"))

        :else
        (responses/ok 201
                      (records/put state
                                   :payments
                                   (assoc (records/resource organisation-id
                                                            "payments"
                                                            id
                                                            attributes)
                                          ::records/direction
                                          :outbound))))))))

(def fetch
  (signed/verified (fn [request]
                     (responses/found (records/get-in-state (:state request)
                                                            :payments
                                                            (path request :id))
                                      "payment"))))

(def search
  (signed/verified
   (fn [request]
     (let [e2e (get-in request
                       [:parameters :query
                        (keyword "filter[end_to_end_reference]")])]
       {:status 200
        :body {:data (mapv records/public
                           (records/find-payments
                            (:state request)
                            (fn [p]
                              (or (nil? e2e)
                                  (= e2e
                                     (get-in p
                                             [:attributes
                                              :end_to_end_reference]))))))}}))))

(def submit
  (signed/verified
   (fn [request]
     (let [{:keys [state]} request
           payment (records/get-in-state state :payments (path request :id))
           submission-id (:id (data request))]
       (cond
        (nil? payment)
        (signed/api-error 404 "The payment does not exist")

        (= :inbound (::records/direction payment))
        (signed/api-error 400 "An inbound payment is not submitted")

        (records/get-in-state state :submissions submission-id)
        (signed/api-error 409 "A submission with this id already exists")

        (= refused-sort-code
           (get-in payment [:attributes :beneficiary_party :bank_id]))
        (signed/api-error 400 "The beneficiary's sort code is not reachable")

        :else
        (responses/ok 201
                      (scheme/submit state
                                     (responses/config request)
                                     payment
                                     submission-id)))))))

(def fetch-submission
  (signed/verified (fn [request]
                     (responses/found
                      (child request :submissions (path request :submissionId))
                      "submission"))))

(def fetch-admission
  (signed/verified (fn [request]
                     (responses/found
                      (child request :admissions (path request :admissionId))
                      "admission"))))

(def fetch-task
  (signed/verified
   (fn [request]
     (responses/found (child request :tasks (path request :taskId)) "task"))))

(def complete-task
  (signed/verified
   (fn [request]
     (let [task-id (path request :taskId)
           {:keys [status output]} (:attributes (data request))]
       (cond
        (nil? (child request :tasks task-id))
        (signed/api-error 404 "The task does not exist")

        (not= "completed" status)
        (signed/api-error 400 "A task is completed with status completed")

        :else
        (let [res (scheme/complete-task (:state request)
                                        task-id
                                        {:status status :output output})]
          (if (= :conflict res)
            (signed/api-error 409 "The task is no longer pending")
            (responses/ok res))))))))

(def create-return
  (signed/verified
   (fn [request]
     (let [{:keys [state organisation-id]} request
           payment-id (path request :id)
           payment (records/get-in-state state :payments payment-id)
           {:keys [id attributes]} (data request)]
       (cond
        (nil? payment)
        (signed/api-error 404 "The payment does not exist")

        (not (and (= :inbound (::records/direction payment))
                  (scheme/returnable? state payment-id)))
        (signed/api-error 409 "Only an admitted inbound is returned, once")

        (not= (get-in payment [:attributes :amount]) (:amount attributes))
        (signed/api-error 400 "A return is for the original amount")

        :else
        (responses/ok 201
                      (records/put state
                                   :returns
                                   (assoc (records/resource
                                           organisation-id
                                           "returns"
                                           id
                                           attributes
                                           {:payment {:data [{:type "payments"
                                                              :id
                                                              payment-id}]}})
                                          ::records/payment-id
                                          payment-id))))))))

(def fetch-return
  (signed/verified (fn [request]
                     (responses/found
                      (child request :returns (path request :returnId))
                      "return"))))

(def submit-return
  (signed/verified
   (fn [request]
     (let [ret (child request :returns (path request :returnId))
           submission-id (:id (data request))]
       (cond
        (nil? ret)
        (signed/api-error 404 "The return does not exist")

        (records/get-in-state (:state request)
                              :return-submissions
                              submission-id)
        (signed/api-error 409 "A submission with this id already exists")

        :else
        (responses/ok 201
                      (scheme/submit-return (:state request)
                                            (responses/config request)
                                            ret
                                            submission-id)))))))

(def fetch-return-submission
  (signed/verified (fn [request]
                     (responses/found (child request
                                             :return-submissions
                                             (path request :submissionId))
                                      "return submission"))))

(def fetch-return-admission
  (signed/verified (fn [request]
                     (responses/found (child request
                                             :return-admissions
                                             (path request :admissionId))
                                      "return admission"))))
