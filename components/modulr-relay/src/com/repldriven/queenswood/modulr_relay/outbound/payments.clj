(ns com.repldriven.queenswood.modulr-relay.outbound.payments
  (:require
    [com.repldriven.queenswood.modulr-relay.outbound.shared :as shared]

    [com.repldriven.queenswood.modulr-relay.outcomes :as outcomes]

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]

    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]))

(defn- rejected
  [intent failure-kind reason now]
  {:event-name "transaction-rejected"
   :dedup-key (str (:dedup-key intent) ":submission-rejected")
   :data {:end-to-end-id (:dedup-key intent)
          :scheme "fps"
          :debit-credit-code :debit-credit-code-debit
          :cancellation-code "NARR"
          :failure-kind failure-kind
          :reason-code "NARR"
          :cancellation-reason reason
          :is-return false
          :timestamp-rejected now}})

(defn- payment-body
  [config intent]
  ;; nosemgrep: unchecked-intent-data — optional in a payment's context
  (let [{:keys [debtor-account-id]} (shared/context intent)
        {:keys [request]} intent
        named (get (json/read-str request) "sourceAccountId")
        held (shared/held-at config debtor-account-id named)]
    (if (= named held)
      request
      (json/write-str (assoc (json/read-str request) "sourceAccountId" held)))))

(defn- pay
  [config _now intent]
  (shared/answer (shared/call config
                              intent
                              {:method :post
                               :path "/payments"
                               :raw-body (payment-body config intent)})))

(defn- paid
  [config now _intent result]
  (shared/sent config now result))

(defn- payment-failed
  [_config now intent failure reason]
  {:status "failed"
   :event (rejected intent
                    (if (= :refused failure)
                      :failure-kind-refused
                      :failure-kind-undelivered)
                    reason
                    now)})

(defn- reconcile-payment
  "Ask Modulr what became of a sent payment no webhook has settled, and
  record what it reports under the dedup key its webhook would carry, so
  a late webhook finds it already there."
  [config now intent]
  (let [{:keys [intent-id dedup-key provider-payment-id]} intent
        {:keys! [amount currency]} (shared/context intent)
        [outcome {:keys [status]}] (shared/lookup config provider-payment-id)
        descriptor (outcomes/payment {:provider-payment-id provider-payment-id
                                      :end-to-end-id dedup-key
                                      :amount amount
                                      :currency currency
                                      :status status
                                      :at now})]
    (assoc (if descriptor
             (do (log/info "Reconciled a Modulr payment"
                           {:intent-id intent-id :status status})
                 {:status "settled" :event descriptor})
             (shared/wait config now))
           :outcome
           outcome)))

(intent-poller/defoperations :modulr
                             {"payment" {:call pay
                                         :answered paid
                                         :failed payment-failed
                                         :reconcile reconcile-payment}})
