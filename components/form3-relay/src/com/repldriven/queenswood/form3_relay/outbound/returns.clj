(ns com.repldriven.queenswood.form3-relay.outbound.returns
  (:require
    [com.repldriven.queenswood.form3-relay.outbound.shared :as shared]

    [com.repldriven.queenswood.form3-relay.outcomes :as outcomes]

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]

    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]))

(defn- return-path
  [provider-payment-id return-id]
  (str (shared/payment-path provider-payment-id) "/returns/" return-id))

(defn- return-failed
  [intent reason]
  (let [{:keys [provider-payment-id]} intent
        {:keys! [end-to-end-id]} (shared/context intent)]
    {:event-name "inbound-return-failed"
     :dedup-key (str provider-payment-id ":return-failed")
     :data {:scheme-transaction-id provider-payment-id
            :end-to-end-id end-to-end-id
            :reason reason}}))

(defn- send-return
  [config _now intent]
  (let [{:keys [request provider-payment-id]} intent
        {:keys! [return-id submission-id]} (shared/context intent)]
    (shared/answer
     (shared/steps config
                   [{:method :post
                     :path (str (shared/payment-path provider-payment-id)
                                "/returns")
                     :body {:data {:id return-id
                                   :type "returns"
                                   :attributes (json/read-str request
                                                              :key-fn
                                                              keyword)}}}
                    {:method :post
                     :path (str (return-path provider-payment-id return-id)
                                "/submissions")
                     :body {:data {:id submission-id
                                   :type "return_submissions"}}}]))))

(defn- returned
  [config now intent _result]
  (shared/sent config now intent))

(defn- return-not-taken
  "Form3 did not take the return, so the inbound stays in suspense."
  [_config _now intent failure reason]
  {:status :outbound-intent-status-failed
   :event (return-failed intent (shared/undelivered failure reason))})

(defn- reconcile-return
  "Ask Form3 what became of a submitted return, and report a delivered
  one as the inbound it sent back returned."
  [config now intent]
  (let [{:keys [intent-id provider-payment-id]} intent
        {:keys! [return-id submission-id end-to-end-id amount currency
                 reason-code]
         :keys [reason]}
        (shared/context intent)
        [outcome result] (shared/call config
                                      {:method :get
                                       :path (str (return-path
                                                   provider-payment-id
                                                   return-id)
                                                  "/submissions/"
                                                  submission-id)})
        status (when (= :ok outcome)
                 (get-in result [:data :attributes :status]))
        descriptor (outcomes/returned {:provider-payment-id provider-payment-id
                                       :end-to-end-id end-to-end-id
                                       :amount amount
                                       :currency currency
                                       :reason-code reason-code
                                       :reason reason
                                       :status status
                                       :at now})]
    (assoc
     (cond
      descriptor
      (do (log/info "Form3 delivered a return" {:intent-id intent-id})
          {:status :outbound-intent-status-settled :event descriptor})

      (= :failed (outcomes/outcome status))
      (do (log/error "Form3 did not deliver a return; it stays in suspense"
                     {:intent-id intent-id :status status})
          {:status :outbound-intent-status-failed
           :event (return-failed intent
                                 (or (get-in result
                                             [:data :attributes :status_reason])
                                     (str "Return " status)))})

      :else
      (shared/wait config now))
     :outcome
     outcome)))

(intent-poller/defoperations :form3
                             {:form3-outbound-intent-kind-return
                              {:call send-return
                               :answered returned
                               :failed return-not-taken
                               :reconcile reconcile-return}})
