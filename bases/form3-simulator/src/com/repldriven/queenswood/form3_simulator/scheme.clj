(ns com.repldriven.queenswood.form3-simulator.scheme
  "What happens once Form3 has a payment. The test values every payment
  simulator shares decide an outbound's outcome: a beneficiary sort code
  `000000` is declined, and the beneficiary name `6a41a29eafcf455493`
  held on a limit check then declined. A payment to an account
  registered here is an inbound to it, and one to anywhere else is
  delivered.

  An inbound is admitted before it settles. Form3 checks the account is
  registered and open itself, failing the admission otherwise, then
  gives the bank an `account_check` task and waits for it to be
  completed, failing the admission when `admission-deadline-ms` passes
  first. Form3 screens nothing, so no inbound is held."
  (:require
    [com.repldriven.queenswood.form3-simulator.deliveries :as deliveries]
    [com.repldriven.queenswood.form3-simulator.records :as records]

    [com.repldriven.mono.log.interface :as log]))

(def held-name "6a41a29eafcf455493")

(def declined-sort-code "000000")

(def ^:private default-admission-deadline-ms 5000)

(def ^:private timeout-reason "beneficiary_agent_clearing_process_timeout")

(defn- pause
  [{:keys [webhook-delay-ms]}]
  (when (pos? (or webhook-delay-ms 0)) (Thread/sleep (long webhook-delay-ms))))

(defn- notify
  [state config record-type event-type r]
  (deliveries/notify state (:organisation-id config) record-type event-type r))

(defn- ref-to
  [type id]
  {:data [{:type type :id id}]})

;; ---- inbound

(defn- decide
  [state config admission-id status reason]
  (let [admission (records/change state
                                  :admissions
                                  admission-id
                                  (fn [a]
                                    (assoc a
                                           :status status
                                           :status_reason reason
                                           :admission_datetime
                                           (records/timestamp))))]
    (notify state config "payment_admissions" "updated" admission)
    {:payment-id (::records/payment-id admission)
     :admission-status status
     :status-reason reason}))

(defn- open-task
  [state config payment-id admission-id]
  (let [task (records/put state
                          :tasks
                          (assoc (records/resource
                                  (:organisation-id config)
                                  "payment_admission_tasks"
                                  (records/new-id)
                                  {:name "account_check"
                                   :assignee "customer"
                                   :group "accounting"
                                   :status "pending"
                                   :workflow (records/new-id)}
                                  {:payment (ref-to "payments" payment-id)
                                   :payment_admission (ref-to
                                                       "payment_admissions"
                                                       admission-id)})
                                 ::records/payment-id
                                 payment-id))]
    (notify state config "payment_admission_tasks" "created" task)
    task))

(defn- await-decision
  [state config admission-id task]
  (let [deadline (or (:admission-deadline-ms config)
                     default-admission-deadline-ms)
        decided (deref (records/decision state admission-id) deadline nil)]
    (if decided
      (decide state config admission-id (:status decided) (:reason decided))
      (do (records/change state
                          :tasks
                          (:id task)
                          (fn [a] (assoc a :status "failed")))
          (log/info "Form3 simulator admission timed out"
                    {:admission-id admission-id})
          (decide state config admission-id "failed" timeout-reason)))))

(defn admit
  "Receive an inbound to the registered `account` and admit it, blocking
  until it is decided: `{:payment-id :admission-status :status-reason}`."
  [state config
   {:keys [account amount currency reference debtor-name debtor
           end-to-end-reference]}]
  (let [payment-id (records/new-id)
        admission-id (records/new-id)
        {:keys [bank_id account_number name status]} (:attributes account)
        org (:organisation-id config)]
    (records/put
     state
     :payments
     (assoc (records/resource
             org
             "payments"
             payment-id
             {:amount amount
              :currency currency
              :reference reference
              :end_to_end_reference (or end-to-end-reference payment-id)
              :payment_scheme "FPS"
              :scheme_payment_type "ImmediatePayment"
              :unique_scheme_id (str "FP" payment-id)
              :beneficiary_party {:account_number account_number
                                  :bank_id bank_id
                                  :bank_id_code "GBDSC"
                                  :account_name (first name)}
              :debtor_party (merge {:account_name debtor-name
                                    :name debtor-name}
                                   debtor)}
             {:payment_admission (ref-to "payment_admissions" admission-id)})
            ::records/direction
            :inbound))
    (records/put state
                 :admissions
                 (assoc (records/resource org
                                          "payment_admissions"
                                          admission-id
                                          {:status "pending"
                                           :scheme_received_datetime
                                           (records/timestamp)}
                                          {:payment (ref-to "payments"
                                                            payment-id)})
                        ::records/payment-id
                        payment-id))
    (if (= "closed" status)
      (decide state config admission-id "failed" "account_closed")
      (await-decision state
                      config
                      admission-id
                      (open-task state config payment-id admission-id)))))

(defn complete-task
  "Complete an admission task as the bank does: the task, or `:conflict`
  when it is no longer pending."
  [state task-id {:keys [status output]}]
  (let [task (records/get-in-state state :tasks task-id)]
    (if (not= "pending" (get-in task [:attributes :status]))
      :conflict
      (let [passed? (= "passed" (:outcome output))
            updated (records/change state
                                    :tasks
                                    task-id
                                    (fn [a]
                                      (assoc a :status status :output output)))
            admission-id (get-in task
                                 [:relationships :payment_admission :data 0
                                  :id])]
        (deliver (records/decision state admission-id)
                 (if passed?
                   {:status "confirmed" :reason "accepted"}
                   {:status "failed"
                    :reason (or (:status_reason output)
                                "rejected_by_customer")}))
        updated))))

;; ---- outbound

(defn- finish-submission
  [state config submission-id status reason]
  (let [s (records/change state
                          :submissions
                          submission-id
                          (fn [a]
                            (cond-> (assoc a :status status)
                                    reason
                                    (assoc :status_reason reason))))]
    (notify state config "payment_submissions" "updated" s)
    s))

(defn- on-us
  "Deliver an outbound to an account registered here as an inbound to
  it, and fail it where that inbound is not admitted."
  [state config payment submission-id account]
  (let [{:keys [amount currency reference end_to_end_reference debtor_party]}
        (:attributes payment)
        {:keys [admission-status status-reason]}
        (admit state
               config
               {:account account
                :amount amount
                :currency currency
                :reference reference
                :end-to-end-reference end_to_end_reference
                :debtor-name (:account_name debtor_party)
                :debtor (select-keys debtor_party
                                     [:account_number :bank_id
                                      :bank_id_code])})]
    (if (= "confirmed" admission-status)
      (finish-submission state config submission-id "delivery_confirmed" nil)
      (finish-submission state
                         config
                         submission-id
                         "delivery_failed"
                         status-reason))))

(defn- process-submission
  [state config payment submission-id]
  (let [{:keys [bank_id account_number account_name name]}
        (get-in payment [:attributes :beneficiary_party])
        target (when (= (:sort-code config) bank_id)
                 (records/account-by-number state bank_id account_number))]
    (pause config)
    (cond
     (= declined-sort-code bank_id)
     (finish-submission state
                        config
                        submission-id
                        "delivery_failed"
                        "invalid_beneficiary_details")

     (= held-name (or account_name name))
     (do (finish-submission state
                            config
                            submission-id
                            "limit_check_pending"
                            nil)
         (pause config)
         (finish-submission state
                            config
                            submission-id
                            "limit_check_failed"
                            "business_reasons"))

     target
     (on-us state config payment submission-id target)

     :else
     (finish-submission state config submission-id "delivery_confirmed" nil))))

(defn submit
  "Accept a payment's submission and process it on another thread, as
  Form3 answers the request before the scheme does."
  [state config payment submission-id]
  (let [s (records/put state
                       :submissions
                       (assoc (records/resource (:organisation-id config)
                                                "payment_submissions"
                                                submission-id
                                                {:status "accepted"
                                                 :submission_datetime
                                                 (records/timestamp)}
                                                {:payment (ref-to "payments"
                                                                  (:id
                                                                   payment))})
                              ::records/payment-id
                              (:id payment)))]
    (future
     (try (process-submission state config payment submission-id)
          (catch Exception e
            (log/error e "Form3 simulator submission processing threw"))))
    s))

(defn delivered?
  "True for an outbound whose latest submission was delivered."
  [state payment-id]
  (= "delivery_confirmed"
     (get-in (last (records/related state :submissions payment-id))
             [:attributes :status])))

;; ---- returns

(defn returnable?
  "True for an inbound that was admitted and has not been returned."
  [state payment-id]
  (let [admission (last (records/related state :admissions payment-id))]
    (and (= "confirmed" (get-in admission [:attributes :status]))
         (empty? (records/related state :returns payment-id)))))

(defn submit-return
  "Accept a return's submission and deliver it on another thread."
  [state config ret submission-id]
  (let [s (records/put state
                       :return-submissions
                       (assoc (records/resource (:organisation-id config)
                                                "return_submissions"
                                                submission-id
                                                {:status "accepted"}
                                                {:return (ref-to "returns"
                                                                 (:id ret))})
                              ::records/payment-id
                              (::records/payment-id ret)))]
    (future
     (try
       (pause config)
       (notify state
               config
               "return_submissions"
               "updated"
               (records/change state
                               :return-submissions
                               submission-id
                               (fn [a] (assoc a :status "delivery_confirmed"))))
       (catch Exception e
         (log/error e "Form3 simulator return processing threw"))))
    s))

(defn return-outbound
  "The beneficiary's bank returning a delivered outbound: records the
  return and its admission and tells the bank. `{:payment-id}`, nil for
  no such payment, or `{:refused message}`."
  [state config end-to-end-reference return-code]
  (let [payment (first (records/find-payments
                        state
                        (fn [p]
                          (and (not= :inbound (::records/direction p))
                               (= end-to-end-reference
                                  (get-in p
                                          [:attributes
                                           :end_to_end_reference]))))))
        payment-id (:id payment)]
    (cond
     (nil? payment)
     nil

     (not (delivered? state payment-id))
     {:refused "Only a delivered payment can be returned"}

     (seq (records/related state :returns payment-id))
     {:refused "The payment has already been returned"}

     :else
     (let [org (:organisation-id config)
           {:keys [amount currency end_to_end_reference]} (:attributes payment)
           ret (records/put state
                            :returns
                            (assoc (records/resource org
                                                     "returns"
                                                     (records/new-id)
                                                     {:amount amount
                                                      :currency currency
                                                      :end_to_end_reference
                                                      end_to_end_reference
                                                      :return_code return-code}
                                                     {:payment (ref-to
                                                                "payments"
                                                                payment-id)})
                                   ::records/payment-id
                                   payment-id))
           admission (records/put state
                                  :return-admissions
                                  (assoc
                                   (records/resource
                                    org
                                    "return_admissions"
                                    (records/new-id)
                                    {:status "confirmed"
                                     :admission_datetime (records/timestamp)}
                                    {:payment (ref-to "payments" payment-id)
                                     :return (ref-to "returns" (:id ret))})
                                   ::records/payment-id
                                   payment-id))]
       (notify state config "return_admissions" "created" admission)
       {:payment-id payment-id}))))
