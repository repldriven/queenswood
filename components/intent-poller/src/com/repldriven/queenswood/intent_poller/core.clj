(ns com.repldriven.queenswood.intent-poller.core
  (:require
    [com.repldriven.queenswood.intent-poller.operations :as operations]
    [com.repldriven.queenswood.intent-poller.store :as store]

    [com.repldriven.queenswood.intent-queue.interface :as intent-queue]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private default-poll-ms 200)
(def ^:private default-max-attempts 20)
(def ^:private default-initial-backoff-ms 1000)
(def ^:private default-max-backoff-ms 60000)

(defn- backoff-ms
  [config attempts]
  (let [{:keys [initial-backoff-ms max-backoff-ms]} config
        initial (or initial-backoff-ms default-initial-backoff-ms)
        cap (or max-backoff-ms default-max-backoff-ms)]
    (reduce (fn [delay _] (min cap (* 2 delay)))
            (min cap initial)
            (range (dec attempts)))))

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

(defn- give-up?
  [config attempts]
  (>= attempts (or (:max-attempts config) default-max-attempts)))

(defn- operation-of
  [config intent]
  (or (not-empty (:kind intent)) (:default-operation config)))

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
                        (+ now (backoff-ms config attempts)))))

(defn- attempt
  [config now intent]
  (let [{:keys [adapter]} config
        {:keys [intent-id]} intent
        o (operation-of config intent)]
    (let-nom> [operation (operations/operation adapter o)
               res ((:call operation) config now intent)]
      (let [{:keys [answered failed]} operation
            [outcome result] res
            attempts (inc (or (:attempts intent) 0))
            intent (assoc intent :attempts attempts)]
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

          :retry
          (if (give-up? config attempts)
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
            (retry config now intent attempts result)))))))

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

(defn drain-once
  [config now]
  (let [pending (intents-with-status config "pending")
        sent (intents-with-status config "sent")]
    (when-not (or (error/anomaly? pending) (error/anomaly? sent))
      (intent-queue/drain
       (into pending sent)
       now
       {:settles-first? (or (:settles-first? config) (constantly false))
        :run (fn [intent]
               (in-intent-trace config
                                "outbound"
                                intent
                                (fn []
                                  (checked config
                                           intent
                                           (fn [i]
                                             (attempt config now i))))))})
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
                                              (f config now i))))))))))

(defn start
  [config]
  (let [{:keys [adapter poll-ms]} config
        running (atom true)
        poll-ms (or poll-ms default-poll-ms)
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
