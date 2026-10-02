(ns com.repldriven.queenswood.form3-relay.outbound
  (:require
    [com.repldriven.queenswood.form3-relay.form3 :as form3]
    [com.repldriven.queenswood.form3-relay.outcomes :as outcomes]
    [com.repldriven.queenswood.form3-relay.store :as store]

    [com.repldriven.queenswood.intent-queue.interface :as intent-queue]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.edn :as edn]))

(def ^:private default-poll-ms 200)
(def ^:private default-max-attempts 20)
(def ^:private default-initial-backoff-ms 1000)
(def ^:private default-max-backoff-ms 60000)
(def ^:private default-reconcile-after-ms 300000)

(defn- backoff-ms
  [config attempts]
  (let [{:keys [initial-backoff-ms max-backoff-ms]} config
        initial (or initial-backoff-ms default-initial-backoff-ms)
        cap (or max-backoff-ms default-max-backoff-ms)]
    (reduce (fn [delay _] (min cap (* 2 delay)))
            (min cap initial)
            (range (dec attempts)))))

(defn- reconcile-at
  [config now]
  (+ now (or (:reconcile-after-ms config) default-reconcile-after-ms)))

(defn- context
  [intent]
  (or (some-> (not-empty (:context intent))
              edn/read-string)
      {}))

(defn- event
  [config now intent {:keys [event-name dedup-key data]}]
  (let [{:keys [schemas]} config
        {:keys [intent-id traceparent]} intent]
    (let-nom> [payload (avro/serialize (get schemas event-name) data)]
      (utility/assoc-some {:outbox-id (str (utility/uuidv7))
                           :dedup-key dedup-key
                           :event-name event-name
                           :payload payload
                           :correlation-id (str (utility/uuidv7))
                           :causation-id intent-id
                           :created-at now}
                          :traceparent
                          (not-empty traceparent)))))

(defn- finish
  ([config now intent outcome descriptor]
   (finish config now intent "pending" outcome descriptor))
  ([config now intent status outcome descriptor]
   (let [{:keys [intent-id attempts]} intent]
     (if (nil? descriptor)
       (store/finish config intent-id status outcome attempts nil)
       (let-nom> [e (event config now intent descriptor)]
         (store/finish config intent-id status outcome attempts e))))))

(defn- call
  [config request]
  (form3/classify ((or (:post-fn config) form3/request) config request)))

(defn- give-up?
  [config attempts]
  (>= attempts (or (:max-attempts config) default-max-attempts)))

(defn- retry
  [config now intent attempts reason]
  (let [{:keys [intent-id kind]} intent]
    (log/warn
     "Form3 call failed; will retry"
     {:intent-id intent-id :kind kind :attempt attempts :reason reason})
    (store/mark-attempt config
                        intent-id
                        attempts
                        (+ now (backoff-ms config attempts)))))

(defn- done?
  "True for a create Form3 answered, or refused as one it already has."
  [outcome]
  (contains? #{:ok :exists} outcome))

(defn- steps
  "Make each call in turn while Form3 answers or already has it: the
  first other outcome, or the last answer."
  [config requests]
  (reduce (fn [_ request]
            (let [[outcome :as res] (call config request)]
              (if (done? outcome) res (reduced res))))
          [:ok nil]
          requests))

;; ---- payments

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

(defn- payment-path
  [payment-id]
  (str "/v1/transaction/payments/" payment-id))

(defn- relay-payment
  [config now intent]
  (let [{:keys [intent-id request provider-payment-id]} intent
        {:keys! [submission-id]} (context intent)
        [outcome result] (steps config
                                [{:method :post
                                  :path "/v1/transaction/payments"
                                  :body {:data {:id provider-payment-id
                                                :type "payments"
                                                :attributes (json/read-str
                                                             request
                                                             :key-fn
                                                             keyword)}}}
                                 {:method :post
                                  :path (str (payment-path provider-payment-id)
                                             "/submissions")
                                  :body {:data {:id submission-id
                                                :type
                                                "payment_submissions"}}}])
        attempts (inc (or (:attempts intent) 0))
        intent (assoc intent :attempts attempts)]
    (cond
     (done? outcome)
     (store/mark-sent config
                      intent-id
                      provider-payment-id
                      (reconcile-at config now))

     (= :refused outcome)
     (do (log/error "Form3 refused the payment"
                    {:intent-id intent-id :reason result})
         (finish config
                 now
                 intent
                 "failed"
                 (rejected intent :failure-kind-refused result now)))

     (give-up? config attempts)
     (do (log/error "Form3 call giving up after max attempts"
                    {:intent-id intent-id :reason result})
         (finish config
                 now
                 intent
                 "failed"
                 (rejected intent :failure-kind-undelivered result now)))

     :else
     (retry config now intent attempts result))))

;; ---- returns

(defn- return-path
  [provider-payment-id return-id]
  (str (payment-path provider-payment-id) "/returns/" return-id))

(defn- return-failed
  [intent reason]
  (let [{:keys [provider-payment-id]} intent
        {:keys! [end-to-end-id]} (context intent)]
    {:event-name "inbound-return-failed"
     :dedup-key (str provider-payment-id ":return-failed")
     :data {:scheme-transaction-id provider-payment-id
            :end-to-end-id end-to-end-id
            :reason reason}}))

(defn- relay-return
  [config now intent]
  (let [{:keys [intent-id request provider-payment-id]} intent
        {:keys! [return-id submission-id]} (context intent)
        [outcome result] (steps config
                                [{:method :post
                                  :path (str (payment-path provider-payment-id)
                                             "/returns")
                                  :body {:data {:id return-id
                                                :type "returns"
                                                :attributes (json/read-str
                                                             request
                                                             :key-fn
                                                             keyword)}}}
                                 {:method :post
                                  :path (str (return-path provider-payment-id
                                                          return-id)
                                             "/submissions")
                                  :body {:data {:id submission-id
                                                :type "return_submissions"}}}])
        attempts (inc (or (:attempts intent) 0))
        intent (assoc intent :attempts attempts)]
    (cond
     (done? outcome)
     (store/mark-sent config
                      intent-id
                      provider-payment-id
                      (reconcile-at config now))

     (or (= :refused outcome) (give-up? config attempts))
     (do (log/error "Form3 did not take the return; it stays in suspense"
                    {:intent-id intent-id :reason result})
         (finish config
                 now
                 intent
                 "failed"
                 (return-failed intent
                                (if (= :refused outcome)
                                  result
                                  (str "Undelivered: " result)))))

     :else
     (retry config now intent attempts result))))

;; ---- accounts

(defn- account-event
  [intent event-name data]
  {:event-name event-name
   :dedup-key (str (:dedup-key intent) ":" event-name)
   :data data})

(defn- account-path
  [id]
  (str "/v1/organisation/accounts/" id))

(defn- addresses
  [{:keys [bank_id account_number]}]
  [{:scheme "scan" :sort-code bank_id :account-number account_number}])

(defn- advance
  "Keep `ctx` for the intent's next attempt, from a fresh start."
  [config intent ctx]
  (store/update-intent config
                       (:intent-id intent)
                       "pending"
                       (fn [i]
                         (-> i
                             (assoc :context (pr-str ctx) :attempts 0)
                             (dissoc :next-attempt-at)))
                       nil))

(defn- with-number
  "The context with a registration id and an account number issued
  under the configured sort code, issuing them where it has none."
  [config intent ctx]
  (if (:account-number ctx)
    ctx
    (let-nom> [number (store/allocate-account-number config)
               ctx (assoc ctx
                          :registration-id (str (utility/uuidv7))
                          :account-number number)
               _ (advance config intent ctx)]
      ctx)))

(defn- registration
  [config {:keys! [registration-id account-number holder-name currency]}]
  {:method :post
   :path "/v1/organisation/accounts"
   :body {:data {:id registration-id
                 :type "accounts"
                 :attributes {:bank_id (:sort-code config)
                              :bank_id_code "GBDSC"
                              :account_number account-number
                              :country "GB"
                              :base_currency (or currency "GBP")
                              :name [holder-name]
                              :account_classification "personal"}}}})

(defn- register
  "Register the context's account, reading it back where Form3 already
  holds it: `[outcome account-or-reason]`."
  [config ctx]
  (let [[outcome result] (call config (registration config ctx))]
    (case outcome
      :ok [:ok (:data result)]
      :exists (let [[o r] (call config
                                {:method :get
                                 :path (account-path (:registration-id ctx))})]
                [o (if (= :ok o) (:data r) r)])
      [outcome result])))

(defn- account-refused
  [intent ctx reason]
  (account-event intent
                 "payment-account-refused"
                 {:bank-id (:bank-id ctx)
                  :account-id (:account-id ctx)
                  :reason reason}))

(defn- relay-open
  [config now intent]
  (let-nom> [ctx (with-number config intent (context intent))]
    (let [{:keys [intent-id]} intent
          {:keys! [bank-id account-id]} ctx
          attempts (inc (or (:attempts intent) 0))
          [outcome result] (register config ctx)
          intent (assoc intent :attempts attempts :context (pr-str ctx))
          {:keys [status status_reason]} (:attributes result)]
      (cond
       (and (= :ok outcome) (= "confirmed" status))
       (finish config
               now
               intent
               "settled"
               (account-event intent
                              "payment-account-opened"
                              {:bank-id bank-id
                               :account-id account-id
                               :provider-account-id (:id result)
                               :addresses (addresses (:attributes result))}))

       (= :ok outcome)
       (do (log/error "Form3 did not confirm an account registration"
                      {:intent-id intent-id :status status})
           (finish config
                   now
                   intent
                   "failed"
                   (account-refused intent
                                    ctx
                                    (or status_reason
                                        (str "Registration " status)))))

       (= :refused outcome)
       (finish config now intent "failed" (account-refused intent ctx result))

       (give-up? config attempts)
       (finish config
               now
               intent
               "failed"
               (account-refused intent ctx (str "Undelivered: " result)))

       :else
       (retry config now intent attempts result)))))

(defn- close-registration
  "Read the registration for its version, then close it."
  [config provider-account-id]
  (let [[outcome result] (call config
                               {:method :get
                                :path (account-path provider-account-id)})]
    (if (not= :ok outcome)
      [outcome result]
      (call config
            {:method :patch
             :path (account-path provider-account-id)
             :body {:data {:id provider-account-id
                           :type "accounts"
                           :version (get-in result [:data :version] 0)
                           :attributes {:status "closed"}}}}))))

(defn- relay-close
  [config now intent]
  (let [{:keys [intent-id]} intent
        {:keys! [bank-id account-id provider-account-id]} (context intent)
        attempts (inc (or (:attempts intent) 0))
        [outcome result] (close-registration config provider-account-id)
        intent (assoc intent :attempts attempts)]
    (cond
     (= :ok outcome)
     (finish config
             now
             intent
             "settled"
             (account-event intent
                            "payment-account-closed"
                            {:bank-id bank-id :account-id account-id}))

     (or (= :refused outcome) (give-up? config attempts))
     (do (log/error
          "Form3 did not close the account"
          {:intent-id intent-id :account-id account-id :reason result})
         (finish config
                 now
                 intent
                 "failed"
                 (account-event intent
                                "payment-account-close-refused"
                                {:bank-id bank-id
                                 :account-id account-id
                                 :reason (if (= :refused outcome)
                                           result
                                           (str "Undelivered: " result))})))

     :else
     (retry config now intent attempts result))))

(defn- reissue-step
  "Read the old registration for its holder, register a new number, close
  the old registration."
  [config ctx]
  (let [{:keys! [step provider-account-id]} ctx]
    (case step
      "read"
      (let [[outcome result] (call config
                                   {:method :get
                                    :path (account-path provider-account-id)})]
        [outcome
         result
         (fn [r]
           (let [{:keys [name base_currency]} (get-in r [:data :attributes])]
             (assoc ctx
                    :step "register"
                    :holder-name (first name)
                    :currency base_currency)))])

      "register"
      (let [[outcome result] (register config ctx)
            {:keys [status status_reason]} (:attributes result)]
        [(if (and (= :ok outcome) (not= "confirmed" status)) :refused outcome)
         (if (and (= :ok outcome) (not= "confirmed" status))
           (or status_reason (str "Registration " status))
           result)
         (fn [account]
           (assoc ctx
                  :step "close"
                  :new-account {:id (:id account)
                                :addresses (addresses (:attributes
                                                       account))}))])

      "close"
      (let [[outcome result] (close-registration config provider-account-id)]
        [outcome result (fn [_] (assoc ctx :step "done"))]))))

(defn- reissued
  [intent ctx]
  (let [{:keys! [bank-id account-id rotation-key new-account]} ctx]
    (account-event intent
                   "payment-address-reissued"
                   {:bank-id bank-id
                    :account-id account-id
                    :provider-account-id (:id new-account)
                    :rotation-key rotation-key
                    :addresses (:addresses new-account)})))

(defn- reissue-failed
  [intent ctx reason]
  (let [{:keys! [bank-id account-id rotation-key]} ctx]
    (account-event intent
                   "payment-address-reissue-failed"
                   {:bank-id bank-id
                    :account-id account-id
                    :rotation-key rotation-key
                    :reason reason})))

(defn- relay-reissue
  [config now intent]
  (let [ctx (context intent)
        ctx (cond-> ctx
                    (nil? (:step ctx))
                    (assoc :step "read"))]
    (if (= "done" (:step ctx))
      (finish config now intent "settled" (reissued intent ctx))
      (let-nom> [ctx (if (= "register" (:step ctx))
                       (with-number config intent ctx)
                       ctx)]
        (let [[outcome result next-ctx] (reissue-step config ctx)
              attempts (inc (or (:attempts intent) 0))
              intent (assoc intent :attempts attempts)
              stop? (or (= :refused outcome) (give-up? config attempts))]
          (cond
           (= :ok outcome)
           (advance config intent (next-ctx result))

           (and stop? (= "close" (:step ctx)))
           (do (log/error
                "Form3 did not close the old registration; it stays open"
                {:intent-id (:intent-id intent)
                 :provider-account-id (:provider-account-id ctx)
                 :reason result})
               (finish config now intent "settled" (reissued intent ctx)))

           stop?
           (do (log/error "Form3 address reissue failed"
                          {:intent-id (:intent-id intent)
                           :step (:step ctx)
                           :reason result})
               (finish config
                       now
                       intent
                       "failed"
                       (reissue-failed intent
                                       ctx
                                       (if (= :refused outcome)
                                         result
                                         (str "Undelivered: " result)))))

           :else
           (retry config now intent attempts result)))))))

;; ---- reconciliation

(defn- lookup
  [config provider-payment-id submission-id]
  (let [[outcome result] (call config
                               {:method :get
                                :path (str (payment-path provider-payment-id)
                                           "/submissions/"
                                           submission-id)})]
    (when (= :ok outcome) (get-in result [:data :attributes]))))

(defn- wait
  [config now intent]
  (store/update-intent
   config
   (:intent-id intent)
   "sent"
   (fn [i] (assoc i :next-attempt-at (reconcile-at config now)))
   nil))

(defn- reconcile-payment
  "Ask Form3 what became of a submitted payment no notification has
  settled, and record what it reports under the dedup key its
  notification would carry, so a late one finds it already there."
  [config now intent]
  (let [{:keys [intent-id dedup-key provider-payment-id]} intent
        {:keys! [amount currency submission-id]} (context intent)
        {:keys [status status_reason]}
        (lookup config provider-payment-id submission-id)
        descriptor (outcomes/payment {:provider-payment-id provider-payment-id
                                      :end-to-end-id dedup-key
                                      :amount amount
                                      :currency currency
                                      :status status
                                      :status-reason status_reason
                                      :at now})]
    (if descriptor
      (do (log/info "Reconciled a Form3 payment"
                    {:intent-id intent-id :status status})
          (finish config now intent "sent" "settled" descriptor))
      (wait config now intent))))

(defn- reconcile-return
  "Ask Form3 what became of a submitted return, and report a delivered
  one as the inbound it sent back returned."
  [config now intent]
  (let [{:keys [intent-id provider-payment-id]} intent
        {:keys! [return-id submission-id end-to-end-id amount currency
                 reason-code reason]}
        (context intent)
        [outcome result] (call config
                               {:method :get
                                :path (str (return-path provider-payment-id
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
    (cond
     descriptor
     (do (log/info "Form3 delivered a return" {:intent-id intent-id})
         (finish config now intent "sent" "settled" descriptor))

     (= :failed (outcomes/outcome status))
     (do (log/error "Form3 did not deliver a return; it stays in suspense"
                    {:intent-id intent-id :status status})
         (finish config
                 now
                 intent
                 "sent"
                 "failed"
                 (return-failed intent
                                (or (get-in result
                                            [:data :attributes :status_reason])
                                    (str "Return " status)))))

     :else
     (wait config now intent))))

(def ^:private reconciles
  {"payment" reconcile-payment "return" reconcile-return})

;; ---- the loop

(def ^:private relays
  {"payment" relay-payment
   "return" relay-return
   "open-account" relay-open
   "close-account" relay-close
   "reissue-address" relay-reissue})

(defn- due?
  [now intent]
  (<= (or (:next-attempt-at intent) 0) now))

(defn- in-intent-trace
  [span-name intent f]
  (telemetry/with-span-parent span-name
                              (telemetry/extract-parent-context intent)
                              (utility/assoc-some {}
                                                  "intent.id"
                                                  (:intent-id intent)
                                                  "intent.kind"
                                                  (:kind intent))
                              f))

(defn drain-once
  "Make each due pending call once, oldest first, holding a call for an
  account while an earlier one for it is unsent, then reconcile every due
  sent payment and return. Reads are transactional; each call and the
  write recording it are separate, so no network I/O happens inside an
  FDB transaction."
  [config now]
  (let [pending (store/intents-with-status config "pending")
        sent (store/intents-with-status config "sent")]
    (when-not (error/anomaly? pending)
      (intent-queue/drain
       pending
       now
       {:settles-first? (constantly false)
        :run (fn [intent]
               (in-intent-trace
                "form3-outbound"
                intent
                (fn []
                  (if-let [relay (get relays (:kind intent))]
                    (relay config now intent)
                    (log/error "Unknown Form3 intent kind"
                               {:intent intent})))))}))
    (when-not (error/anomaly? sent)
      (doseq [intent sent
              :when (due? now intent)
              :let [reconcile (get reconciles (:kind intent))]]
        (in-intent-trace
         "form3-reconcile"
         intent
         (fn []
           (if reconcile
             (reconcile config now intent)
             (log/error "Unknown sent Form3 intent kind"
                        {:intent intent}))))))))

(defn start-runner
  [config]
  (let [running (atom true)
        poll-ms (or (:poll-ms config) default-poll-ms)
        t (doto
            (Thread.
             (fn []
               (while @running
                 (try (drain-once config (utility/now))
                      (catch Exception e
                        (log/error e "Form3 relay drain threw; continuing")))
                 (try (when @running (Thread/sleep poll-ms))
                      (catch InterruptedException _ (reset! running false))))))
            (.setDaemon true)
            (.setName "form3-outbound-relay")
            (.start))]
    {:stop (fn [] (reset! running false) (.interrupt t))}))
