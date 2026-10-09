(ns com.repldriven.queenswood.form3-relay.outbound.payments
  (:require
    [com.repldriven.queenswood.form3-relay.outbound.shared :as shared]

    [com.repldriven.queenswood.form3-relay.outcomes :as outcomes]

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]

    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]))

(defn- rejected
  [intent failure-kind reason now]
  {:event-name "provider-payment-rejected"
   :dedup-key (str (:idempotency-key intent) ":submission-rejected")
   :data {:end-to-end-id (:idempotency-key intent)
          :scheme "fps"
          :debit-credit-code :debit-credit-code-debit
          :cancellation-code "NARR"
          :failure-kind failure-kind
          :reason-code "NARR"
          :cancellation-reason reason
          :is-return false
          :timestamp-rejected now}})

(defn- pay
  [config _now intent]
  (let [{:keys [request provider-payment-id]} intent
        {:keys! [submission-id]} (shared/context intent)]
    (shared/answer (shared/steps
                    config
                    [{:method :post
                      :path "/v1/transaction/payments"
                      :body {:data {:id provider-payment-id
                                    :type "payments"
                                    :attributes
                                    (json/read-str request :key-fn keyword)}}}
                     {:method :post
                      :path (str (shared/payment-path provider-payment-id)
                                 "/submissions")
                      :body {:data {:id submission-id
                                    :type "payment_submissions"}}}]))))

(defn- paid
  [config now intent _result]
  (shared/sent config now intent))

(defn- payment-failed
  [_config now intent failure reason]
  {:status :outbound-intent-status-failed
   :event (rejected intent
                    (if (= :refused failure)
                      :failure-kind-refused
                      :failure-kind-undelivered)
                    reason
                    now)})

(defn- lookup
  "`[outcome attributes]`: Form3's answer to looking the submission up,
  its attributes where it answered, nil otherwise."
  [config provider-payment-id submission-id]
  (let [[outcome result] (shared/call config
                                      {:method :get
                                       :path (str (shared/payment-path
                                                   provider-payment-id)
                                                  "/submissions/"
                                                  submission-id)})]
    [outcome (when (= :ok outcome) (get-in result [:data :attributes]))]))

(defn- reconcile-payment
  "Ask Form3 what became of a submitted payment no notification has
  settled, and record what it reports under the dedup key its
  notification would carry, so a late one finds it already there."
  [config now intent]
  (let [{:keys [intent-id idempotency-key provider-payment-id]} intent
        {:keys! [amount currency submission-id]} (shared/context intent)
        [outcome {:keys [status status_reason]}]
        (lookup config provider-payment-id submission-id)
        descriptor (outcomes/payment {:provider-payment-id provider-payment-id
                                      :end-to-end-id idempotency-key
                                      :amount amount
                                      :currency currency
                                      :status status
                                      :status-reason status_reason
                                      :at now})]
    (assoc (if descriptor
             (do (log/info "Reconciled a Form3 payment"
                           {:intent-id intent-id :status status})
                 {:status :outbound-intent-status-settled :event descriptor})
             (shared/wait config now))
           :outcome
           outcome)))

(intent-poller/defoperations :form3
                             {:form3-outbound-intent-kind-payment
                              {:call pay
                               :answered paid
                               :failed payment-failed
                               :reconcile reconcile-payment}})
