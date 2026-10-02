(ns com.repldriven.queenswood.modulr-relay.outbound
  (:require
    [com.repldriven.queenswood.modulr-relay.modulr :as modulr]
    [com.repldriven.queenswood.modulr-relay.outcomes :as outcomes]
    [com.repldriven.queenswood.modulr-relay.store :as store]

    [com.repldriven.queenswood.intent-queue.interface :as intent-queue]
    [com.repldriven.queenswood.modulr-webhook.interface :as modulr-webhook]

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

(defn- finish-holding
  [config now intent account-id provider-account-id descriptor]
  (let [{:keys [intent-id attempts]} intent]
    (let-nom> [e (event config now intent descriptor)]
      (store/finish-holding config
                            intent-id
                            "pending"
                            "settled"
                            attempts
                            e
                            account-id
                            provider-account-id))))

(defn- held-at
  "The provider account holding `account-id`'s money as this adapter
  last opened or reissued it, or `fallback`, the one the command named,
  where it opened none."
  [config account-id fallback]
  (let [held (when account-id (store/provider-account config account-id))]
    (if (or (nil? held) (error/anomaly? held)) fallback held)))

(defn- call
  "Make one call for `intent`, retrying as the same request: a retry
  sends the nonce the first attempt was signed with, and `x-mod-retry`."
  [config intent request]
  (let [{:keys [post-fn]} config
        {:keys [nonce attempts]} intent
        request (assoc request
                       :nonce (not-empty nonce)
                       :retry? (pos? (or attempts 0)))]
    (modulr/classify ((or post-fn modulr/request) config request))))

(defn- give-up?
  [config attempts]
  (>= attempts (or (:max-attempts config) default-max-attempts)))

(defn- retry
  [config now intent attempts reason]
  (let [{:keys [intent-id kind]} intent]
    (log/warn
     "Modulr call failed; will retry"
     {:intent-id intent-id :kind kind :attempt attempts :reason reason})
    (store/mark-attempt config
                        intent-id
                        attempts
                        (+ now (backoff-ms config attempts)))))

;; ---- payments and transfers

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

(defn- transfer-failed
  [intent reason now]
  (let [{:keys! [bank-id]} (context intent)]
    {:event-name "transfer-failed"
     :dedup-key (str (:dedup-key intent) ":failed")
     :data {:transfer-id (:dedup-key intent)
            :bank-id bank-id
            :reason reason
            :timestamp-failed now}}))

(defn- transfer-completed
  [intent now]
  (let [{:keys! [bank-id]} (context intent)]
    {:event-name "transfer-completed"
     :dedup-key (str (:dedup-key intent) ":completed")
     :data {:transfer-id (:dedup-key intent)
            :bank-id bank-id
            :timestamp-completed now}}))

(defn- failure
  [intent failure-kind reason now]
  (if (= "payment" (:kind intent))
    (rejected intent failure-kind reason now)
    (transfer-failed intent reason now)))

(def ^:private sandbox-payer
  "Who the sandbox credit says paid, since Modulr requires a payer."
  {:name "Sandbox funding"
   :identifier {:type "SCAN" :sortCode "000000" :accountNumber "00000000"}})

(defn- payment-body
  [config intent]
  ;; nosemgrep: unchecked-intent-data — optional in a payment's context
  (let [{:keys [debtor-account-id]} (context intent)
        {:keys [request]} intent
        named (get (json/read-str request) "sourceAccountId")
        held (held-at config debtor-account-id named)]
    (if (= named held)
      request
      (json/write-str (assoc (json/read-str request) "sourceAccountId" held)))))

(defn- transfer-body
  [config intent]
  (let [{:keys! [amount currency]
         :keys [debtor-account-id creditor-account-id
                debtor-provider-account-id creditor-provider-account-id]}
        (context intent)
        debtor (held-at config debtor-account-id debtor-provider-account-id)
        creditor (held-at config
                          creditor-account-id
                          creditor-provider-account-id)
        reference (modulr/->reference (:dedup-key intent))]
    (cond
     (not (or creditor-account-id creditor-provider-account-id))
     (:request intent)

     (nil? creditor)
     nil

     (= "credit" (:kind intent))
     (json/write-str {:accountId creditor
                      :amount (modulr/->major-units amount)
                      :description reference
                      :type "PI_FAST"
                      :payerDetail sandbox-payer})

     debtor
     (json/write-str {:sourceAccountId debtor
                      :destination {:type "ACCOUNT" :id creditor}
                      :amount (modulr/->major-units amount)
                      :currency currency
                      :reference "Ledger transfer"
                      :externalReference reference}))))

(defn- request-body
  [config intent]
  (if (= "payment" (:kind intent))
    (payment-body config intent)
    (transfer-body config intent)))

(defn- relay-payment
  [config now intent]
  (let [{:keys [intent-id kind]} intent
        body (request-body config intent)
        [outcome result] (if body
                           (call config
                                 intent
                                 {:method :post
                                  :path (if (= "credit" kind)
                                          "/credit"
                                          "/payments")
                                  :raw-body body})
                           [:retry "No provider account holds it yet"])
        attempts (inc (or (:attempts intent) 0))
        intent (assoc intent :attempts attempts)]
    (cond
     (and (= :ok outcome) (= "credit" kind))
     (finish config now intent "settled" (transfer-completed intent now))

     (= :ok outcome)
     (store/mark-sent config intent-id (:id result) (reconcile-at config now))

     (= :refused outcome)
     (do (log/error "Modulr refused the call"
                    {:intent-id intent-id :kind kind :reason result})
         (finish config
                 now
                 intent
                 "failed"
                 (failure intent :failure-kind-refused result now)))

     (give-up? config attempts)
     (do (log/error "Modulr call giving up after max attempts"
                    {:intent-id intent-id :kind kind :reason result})
         (finish config
                 now
                 intent
                 "failed"
                 (failure intent :failure-kind-undelivered result now)))

     :else
     (retry config now intent attempts result))))

;; ---- accounts

(defn- scan
  [{:keys [id identifiers]}]
  (let [{:keys [sortCode accountNumber]}
        (or (some (fn [i] (when (= "SCAN" (:type i)) i)) identifiers)
            (first identifiers))]
    {:id id
     :addresses [{:scheme "scan"
                  :sort-code sortCode
                  :account-number accountNumber}]}))

(defn- account-event
  [intent event-name data]
  {:event-name event-name
   :dedup-key (str (:dedup-key intent) ":" event-name)
   :data data})

(defn- open-body
  [intent currency]
  (let [{:keys! [account-id]} (context intent)]
    (utility/assoc-some {:externalReference (modulr/->reference account-id)}
                        :currency
                        currency
                        :productCode
                        (not-empty (get (json/read-str (:request intent))
                                        "productCode")))))

(defn- open-path
  [config]
  (str "/customers/" (:customer-id config) "/accounts"))

(defn- account-refused
  [intent reason]
  (let [{:keys! [bank-id account-id]} (context intent)]
    (account-event intent
                   "payment-account-refused"
                   {:bank-id bank-id :account-id account-id :reason reason})))

(defn- relay-open
  [config now intent]
  (let [{:keys [intent-id request]} intent
        {:keys! [bank-id account-id]} (context intent)
        attempts (inc (or (:attempts intent) 0))
        [outcome result] (call config
                               intent
                               {:method :post
                                :path (open-path config)
                                :raw-body request})
        intent (assoc intent :attempts attempts)]
    (cond
     (= :ok outcome)
     (let [{:keys [id addresses]} (scan result)]
       (finish-holding config
                       now
                       intent
                       account-id
                       id
                       (account-event intent
                                      "payment-account-opened"
                                      {:bank-id bank-id
                                       :account-id account-id
                                       :provider-account-id id
                                       :addresses addresses})))

     (= :refused outcome)
     (do (log/error "Modulr refused an account opening"
                    {:intent-id intent-id :reason result})
         (finish config now intent "failed" (account-refused intent result)))

     (give-up? config attempts)
     (finish config
             now
             intent
             "failed"
             (account-refused intent (str "Undelivered: " result)))

     :else
     (retry config now intent attempts result))))

(defn- close-refused
  [intent reason]
  (let [{:keys! [bank-id account-id]} (context intent)]
    (account-event intent
                   "payment-account-close-refused"
                   {:bank-id bank-id :account-id account-id :reason reason})))

(defn- relay-close
  [config now intent]
  (let [{:keys [intent-id]} intent
        {:keys! [bank-id account-id provider-account-id]} (context intent)
        held (held-at config account-id provider-account-id)
        attempts (inc (or (:attempts intent) 0))
        [outcome result] (call config
                               intent
                               {:method :post
                                :path (str "/accounts/" held "/close")})
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

     (= :refused outcome)
     (do (log/error "Modulr refused to close the account"
                    {:intent-id intent-id
                     :account-id account-id
                     :reason result})
         (finish config now intent "failed" (close-refused intent result)))

     (give-up? config attempts)
     (do (log/error "Modulr did not close the account"
                    {:intent-id intent-id
                     :account-id account-id
                     :reason result})
         (finish config
                 now
                 intent
                 "failed"
                 (close-refused intent (str "Undelivered: " result))))

     :else
     (retry config now intent attempts result))))

(defn- reissue-step
  "The call for the reissue's current step, and how its result advances
  the context: block the old account, read what it holds, open the new
  one, move the balance across, close the old one."
  [config intent ctx]
  (let [{:keys! [step]
         :keys [provider-account-id balance currency new-account]}
        ctx
        old provider-account-id]
    (case step
      "block"
      [{:method :post :path (str "/accounts/" old "/block")}
       (fn [_] (assoc ctx :step "read"))]

      "read"
      [{:method :get :path (str "/accounts/" old)}
       (fn [{:keys [balance currency]}]
         (assoc ctx
                :step "open"
                :balance (str balance)
                :currency currency))]

      "open"
      [{:method :post
        :path (open-path config)
        :body (open-body intent currency)}
       (fn [result] (assoc ctx :step "move" :new-account (scan result)))]

      "move"
      (if (pos? (.signum (bigdec (or (not-empty balance) "0"))))
        [{:method :post
          :path "/payments"
          :body {:sourceAccountId old
                 :destination {:type "ACCOUNT" :id (:id new-account)}
                 :amount (bigdec balance)
                 :currency currency
                 :reference "Address reissue"
                 :externalReference (str "move-" (:intent-id intent))}}
         (fn [_] (assoc ctx :step "close"))]
        [nil (fn [_] (assoc ctx :step "close"))])

      "close"
      [{:method :post :path (str "/accounts/" old "/close")}
       (fn [_] (assoc ctx :step "done"))]

      "unblock"
      [{:method :post :path (str "/accounts/" old "/unblock")}
       (fn [_] (assoc ctx :step "failed"))])))

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
  [intent ctx]
  (let [{:keys! [bank-id account-id rotation-key failure]} ctx]
    (account-event intent
                   "payment-address-reissue-failed"
                   {:bank-id bank-id
                    :account-id account-id
                    :rotation-key rotation-key
                    :reason failure})))

(defn- advance
  [config intent ctx]
  (store/update-intent config
                       (:intent-id intent)
                       "pending"
                       (fn [i]
                         (-> i
                             (assoc :context (pr-str ctx)
                                    :nonce (modulr-webhook/nonce)
                                    :attempts 0)
                             (dissoc :next-attempt-at)))
                       nil))

(defn- relay-reissue
  [config now intent]
  (let [{:keys [intent-id]} intent
        ctx (context intent)
        {:keys! [account-id]} ctx
        ctx (if (:step ctx)
              ctx
              (let [held (held-at config
                                  account-id
                                  (:provider-account-id ctx))]
                (utility/assoc-some (assoc ctx :step (if held "block" "open"))
                                    :provider-account-id
                                    held)))]
    (case (:step ctx)
      "done"
      (finish-holding config
                      now
                      intent
                      account-id
                      (get-in ctx [:new-account :id])
                      (reissued intent ctx))

      "failed"
      (finish config now intent "failed" (reissue-failed intent ctx))

      (let [{:keys! [step] :keys [provider-account-id]} ctx
            [request next-ctx] (reissue-step config intent ctx)
            attempts (inc (or (:attempts intent) 0))
            intent (assoc intent :attempts attempts)
            [outcome result] (if request
                               (call config intent request)
                               [:ok nil])
            stop? (or (= :refused outcome) (give-up? config attempts))
            reason
            (if (= :refused outcome) result (str "Undelivered: " result))]
        (cond
         (= :ok outcome)
         (advance config intent (next-ctx result))

         (and stop? (= "close" step))
         (do (log/error "Modulr did not close the old account; it stays blocked"
                        {:intent-id intent-id
                         :provider-account-id provider-account-id
                         :reason result})
             (finish-holding config
                             now
                             intent
                             account-id
                             (get-in ctx [:new-account :id])
                             (reissued intent ctx)))

         (and stop? (= "unblock" step))
         (do (log/error "Modulr did not unblock the old account"
                        {:intent-id intent-id
                         :provider-account-id provider-account-id
                         :reason result})
             (finish config now intent "failed" (reissue-failed intent ctx)))

         (and stop?
              provider-account-id
              (not (and (= "block" step) (= :refused outcome))))
         (do (log/error "Modulr address reissue failed; unblocking"
                        {:intent-id intent-id
                         :step step
                         :new-provider-account-id (get-in ctx
                                                          [:new-account :id])
                         :reason result})
             (advance config
                      intent
                      (assoc ctx :step "unblock" :failure reason)))

         stop?
         (do (log/error "Modulr address reissue failed"
                        {:intent-id intent-id :step step :reason result})
             (finish config
                     now
                     intent
                     "failed"
                     (reissue-failed intent (assoc ctx :failure reason))))

         :else
         (retry config now intent attempts result))))))

;; ---- reconciliation

(defn- lookup
  [config provider-payment-id]
  (let [{:keys [post-fn]} config
        [outcome result] (modulr/classify ((or post-fn modulr/request)
                                           config
                                           {:method :get
                                            :path "/payments"
                                            :query {"id"
                                                    provider-payment-id}}))]
    (when (= :ok outcome) (first (:content result)))))

(defn- reconcile
  "Ask Modulr what became of a sent payment or transfer no webhook has
  settled, and record what it reports under the dedup key its webhook
  would carry, so a late webhook finds it already there."
  [config now intent]
  (let [{:keys [intent-id kind dedup-key provider-payment-id]} intent
        {:keys! [amount currency] :keys [bank-id]} (context intent)
        {:keys [status]} (lookup config provider-payment-id)
        descriptor (if (= "payment" kind)
                     (outcomes/payment {:provider-payment-id provider-payment-id
                                        :end-to-end-id dedup-key
                                        :amount amount
                                        :currency currency
                                        :status status
                                        :at now})
                     (outcomes/transfer {:provider-payment-id
                                         provider-payment-id
                                         :transfer-id dedup-key
                                         :bank-id bank-id
                                         :status status
                                         :at now}))]
    (if descriptor
      (do (log/info "Reconciled a Modulr payment"
                    {:intent-id intent-id :status status})
          (finish config now intent "sent" "settled" descriptor))
      (store/update-intent
       config
       intent-id
       "sent"
       (fn [i] (assoc i :next-attempt-at (reconcile-at config now)))
       nil))))

;; ---- the loop

(def ^:private relays
  {"payment" relay-payment
   "transfer" relay-payment
   "credit" relay-payment
   "open-account" relay-open
   "close-account" relay-close
   "reissue-address" relay-reissue})

(defn- due?
  [now intent]
  (<= (or (:next-attempt-at intent) 0) now))

(def ^:private
     ^{:doc "Kinds that wait for an account's calls to settle."} settles-first
  #{"close-account" "reissue-address"})

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

(defn- checked
  "Run `f` on `intent`, failing the intent where its stored data lacks a
  key the call needs, so it no longer holds the intents behind it."
  [config intent f]
  (let [res (error/try-nom-ex :modulr-relay/intent
                              IllegalArgumentException
                              "Modulr intent lacks what its call needs"
                              (f intent))]
    (if (error/anomaly? res)
      (let [{:keys [intent-id kind status attempts]} intent]
        (log/error "Modulr intent cannot be relayed; failing it"
                   {:intent-id intent-id :kind kind :anomaly res})
        (store/finish config intent-id status "failed" attempts nil)
        (assoc intent :status "failed"))
      res)))

(defn drain-once
  "Make each due pending call once, oldest first, holding a call for an
  account while an earlier one for it is unsent, and a close or reissue
  while one is unsettled; then reconcile every due sent payment and
  transfer. Reads are transactional; each call and the write recording
  it are separate, so no network I/O happens inside an FDB transaction."
  [config now]
  (let [pending (store/intents-with-status config "pending")
        sent (store/intents-with-status config "sent")]
    (when-not (or (error/anomaly? pending) (error/anomaly? sent))
      (intent-queue/drain
       (into pending sent)
       now
       {:settles-first? (fn [intent] (contains? settles-first (:kind intent)))
        :run (fn [intent]
               (in-intent-trace
                "modulr-outbound"
                intent
                (fn []
                  (if-let [relay (get relays (:kind intent))]
                    (checked config intent (fn [i] (relay config now i)))
                    (log/error "Unknown Modulr intent kind"
                               {:intent intent})))))}))
    (when-not (error/anomaly? sent)
      (doseq [intent sent
              :when (due? now intent)]
        (in-intent-trace "modulr-reconcile"
                         intent
                         (fn []
                           (checked config
                                    intent
                                    (fn [i] (reconcile config now i)))))))))

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
                        (log/error e "Modulr relay drain threw; continuing")))
                 (try (when @running (Thread/sleep poll-ms))
                      (catch InterruptedException _ (reset! running false))))))
            (.setDaemon true)
            (.setName "modulr-outbound-relay")
            (.start))]
    {:stop (fn [] (reset! running false) (.interrupt t))}))
