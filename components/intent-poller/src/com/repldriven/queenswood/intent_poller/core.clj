(ns com.repldriven.queenswood.intent-poller.core
  (:require
    [com.repldriven.queenswood.intent-poller.operations :as operations]
    [com.repldriven.queenswood.intent-poller.store :as store]

    [com.repldriven.queenswood.circuit-breaker.interface :as circuit-breaker]
    [com.repldriven.queenswood.intent-queue.interface :as intent-queue]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]))

(def config-schema
  [:map
   [:delivery-policy circuit-breaker/delivery-policy-schema]
   [:poll-ms pos-int?]])

(defn- outbox-event
  [config now intent descriptor]
  (let [{:keys [schemas]} config
        {:keys [intent-id traceparent]} intent
        {:keys [event-name dedup-key data]} descriptor]
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

(defn- record
  "Leave `intent`, read at `from`, as `outcome` says: kept pending with
  its next step's context, or moved to its status with its event."
  [config now intent from outcome]
  (let [{:keys [store]} config
        {:keys [intent-id attempts]} intent
        {:keys [advance status event changes also]} outcome]
    (if advance
      (store/update-intent config
                           store
                           intent-id
                           from
                           (fn [i] (store/advanced i advance changes))
                           nil)
      (let-nom> [e (when event (outbox-event config now intent event))]
        (store/update-intent config
                             store
                             intent-id
                             from
                             (fn [i] (store/moved i status attempts changes))
                             e
                             also)))))

(defn- operation-of
  [config intent]
  (or (not-empty (:kind intent)) (:default-operation config)))

(defn- retry-policy
  [config intent]
  (circuit-breaker/retry-policy (:delivery-policy config)
                                (operation-of config intent)))

(defn- age-ms
  [now intent]
  (let [{:keys [created-at]} intent]
    (when (and created-at (pos? created-at)) (- now created-at))))

(defn- destination
  [config]
  (str "adapter:" (name (:adapter config))))

(defn- claimant
  [config]
  (or (:runner-id config) "intent-poller"))

(defn- breaker-policy
  [config]
  (get-in config [:delivery-policy :breaker]))

(defn- record-call
  "Record a call's `outcome` on the adapter's breaker, ending the pass's
  calls where it opens the breaker."
  [config now pass outcome]
  (let [breaker (circuit-breaker/record config
                                        (breaker-policy config)
                                        (destination config)
                                        outcome
                                        now)]
    (cond
     (error/anomaly? breaker)
     (log/error "Circuit breaker not recorded"
                {:destination (destination config) :anomaly breaker})

     (= "open" (:state breaker))
     (do (log/warn "Circuit breaker open; calls held"
                   {:destination (destination config)
                    :retry-at (:retry-at breaker)})
         (swap! pass assoc :budget 0)))))

(defn- retry
  [config now intent attempts reason]
  (let [{:keys [adapter store]} config
        {:keys [intent-id]} intent]
    (log/warn "External API call failed; will retry"
              {:adapter adapter
               :intent-id intent-id
               :operation (operation-of config intent)
               :attempt attempts
               :reason reason})
    (store/mark-attempt config
                        store
                        intent-id
                        attempts
                        (+ now
                           (circuit-breaker/backoff-ms (retry-policy config
                                                                     intent)
                                                       (max 1 attempts))))))

(defn- attempt
  [config now pass intent]
  (let [{:keys [adapter]} config
        {:keys [intent-id]} intent
        o (operation-of config intent)]
    (let-nom> [operation (operations/operation adapter o)
               res ((:call operation) config now intent)]
      (let [{:keys [answered failed]} operation
            [outcome result] res
            attempts (inc (or (:attempts intent) 0))
            intent (assoc intent :attempts attempts)]
        (when-not (= :wait outcome)
          (record-call config
                       now
                       pass
                       (if (= :retry outcome) :failed :answered)))
        (case outcome
          :answered
          (record config
                  now
                  intent
                  "pending"
                  (answered config now intent result))

          :refused
          (do (log/error "The external API refused the call"
                         {:adapter adapter
                          :intent-id intent-id
                          :operation o
                          :reason result})
              (record config
                      now
                      intent
                      "pending"
                      (failed config now intent :refused result)))

          (:retry :wait)
          (if (circuit-breaker/give-up? (retry-policy config intent)
                                        (if (= :wait outcome) 0 attempts)
                                        (age-ms now intent))
            (do (log/error "External API call giving up after max attempts"
                           {:adapter adapter
                            :intent-id intent-id
                            :operation o
                            :reason result})
                (record config
                        now
                        intent
                        "pending"
                        (failed config now intent :undelivered result)))
            (retry config
                   now
                   intent
                   (if (= :wait outcome) (dec attempts) attempts)
                   result)))))))

(defn- reconciler
  "The operation's `:reconcile`, where it has one."
  [config intent]
  (let [operation (operations/operation (:adapter config)
                                        (operation-of config intent))]
    (when (map? operation) (:reconcile operation))))

(defn- in-intent-trace
  [config span intent f]
  (telemetry/with-span-parent (str (name (:adapter config)) "-" span)
                              (telemetry/extract-parent-context intent)
                              (utility/assoc-some {}
                                                  "intent.id"
                                                  (:intent-id intent)
                                                  "intent.kind"
                                                  (not-empty (:kind intent)))
                              f))

(defn- checked
  [config intent f]
  (let [res (error/try-nom-ex :intent-poller/intent
                              Exception
                              "Intent could not be relayed"
                              (f intent))]
    (if (error/anomaly? res)
      (let [{:keys [adapter store]} config
            {:keys [intent-id status attempts]} intent]
        (log/error "Intent could not be relayed; failing it"
                   {:adapter adapter
                    :intent-id intent-id
                    :operation (operation-of config intent)
                    :anomaly res})
        (store/finish config store intent-id status "failed" attempts nil)
        (assoc intent :status "failed"))
      res)))

(defn intents-with-status
  [config status]
  (store/intents-with-status config (:store config) status))

(defn- due?
  [now intent]
  (<= (or (:next-attempt-at intent) 0) now))

(def ^:private expired
  "Why an intent the breaker held past its maximum age failed."
  "The external API did not answer before the intent expired")

(defn- expire
  [config now intent]
  (let-nom> [operation (operations/operation (:adapter config)
                                             (operation-of config intent))]
    (log/error "Intent expired while its external API was unreachable"
               {:adapter (:adapter config) :intent-id (:intent-id intent)})
    (record config
            now
            intent
            "pending"
            ((:failed operation) config now intent :undelivered expired))))

(defn- expire-aged
  "Fail each pending intent past its maximum age, which a breaker open
  for longer has kept from being attempted."
  [config now pending]
  (doseq [intent pending
          :let [{:keys [max-age-ms]} (retry-policy config intent)
                age (age-ms now intent)]
          :when (and age (> age max-age-ms))]
    (in-intent-trace config
                     "outbound"
                     intent
                     (fn []
                       (checked config
                                intent
                                (fn [i] (expire config now i)))))))

(defn- allowed
  "What the adapter's breaker lets this pass do: `:closed`, `:probe` or
  `:open`. A breaker that cannot be read lets the pass through."
  [config now]
  (let [decision (circuit-breaker/allow config
                                        (breaker-policy config)
                                        (destination config)
                                        now
                                        (claimant config))]
    (if (error/anomaly? decision)
      (do (log/error "Circuit breaker not read; calling as though closed"
                     {:destination (destination config) :anomaly decision})
          :closed)
      decision)))

(defn drain-once
  [config now]
  (let [pending (intents-with-status config "pending")
        sent (intents-with-status config "sent")]
    (when-not (or (error/anomaly? pending) (error/anomaly? sent))
      (let [decision (allowed config now)
            pass (atom {:budget (if (= :probe decision) 1 ##Inf)})]
        (if (= :open decision)
          (expire-aged config now pending)
          (do
            (intent-queue/drain
             (into pending sent)
             now
             {:settles-first? (or (:settles-first? config) (constantly false))
              :run (fn [intent]
                     (if (pos? (:budget @pass))
                       (do (swap! pass update :budget dec)
                           (in-intent-trace
                            config
                            "outbound"
                            intent
                            (fn []
                              (checked config
                                       intent
                                       (fn [i] (attempt config now pass i))))))
                       intent))})
            (when (and (= :closed decision) (pos? (:budget @pass)))
              (doseq [intent sent
                      :let [f (reconciler config intent)]
                      :when (and f (due? now intent))]
                (in-intent-trace config
                                 "reconcile"
                                 intent
                                 (fn []
                                   (checked config
                                            intent
                                            (fn [i]
                                              (record config
                                                      now
                                                      i
                                                      "sent"
                                                      (f config
                                                         now
                                                         i))))))))))))))

(defn start
  [config]
  (let [{:keys [adapter poll-ms]} config
        config (assoc config :runner-id (str (utility/uuidv7)))
        running (atom true)
        t (doto (Thread.
                 (fn []
                   (while @running
                     (try
                       (drain-once config (utility/now))
                       (catch Exception e
                         (log/error e "Intent poller drain threw; continuing")))
                     (try (when @running (Thread/sleep poll-ms))
                          (catch InterruptedException _
                            (reset! running false))))))
            (.setDaemon true)
            (.setName (str (name adapter) "-intent-poller"))
            (.start))]
    {:stop (fn [] (reset! running false) (.interrupt t))}))
