(ns com.repldriven.queenswood.payment.sweep
  (:require
    [com.repldriven.queenswood.payment.core :as core]
    [com.repldriven.queenswood.payment.domain :as domain]
    [com.repldriven.queenswood.payment.events :as events]
    [com.repldriven.queenswood.payment.store :as store]

    [com.repldriven.queenswood.payment-query.interface :as q]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]))

(defn sweep-once
  [config now]
  (let [result (let-nom>
                 [pending (q/find-outbound-payments-by-status
                           config
                           :outbound-payment-status-pending)
                  held (q/find-outbound-payments-by-status
                        config
                        :outbound-payment-status-held)
                  actions (domain/sweep-actions (into pending held) now config)
                  {:keys [republish report]} actions]
                 (doseq [payment republish]
                   (core/republish-pending config payment))
                 (doseq [stuck report]
                   (log/error "Outbound payment stuck" stuck))
                 actions)]
    (when (error/anomaly? result)
      (log/error "Outbound sweep failed to read payments" result))
    result))

(defn transfer-sweep-once
  "Send again every provider transfer left pending past
  `resend-after-ms`: one whose provider account was not opened when it
  was recorded, or one the adapter has not yet reported. The adapter
  takes a transfer it already has as that transfer."
  [config now]
  (let [{:keys [resend-after-ms]} config
        result (let-nom> [pending (store/pending-transfers config)]
                 (doseq [transfer pending
                         :when (> (- now (:created-at transfer))
                                  resend-after-ms)]
                   (let [res (events/send-transfer config transfer)]
                     (when (error/anomaly? res)
                       (log/error "Provider transfer resend failed"
                                  {:transfer-id (:transfer-id transfer)
                                   :anomaly res}))))
                 pending)]
    (when (error/anomaly? result)
      (log/error "Transfer sweep failed to read transfers" result))
    result))

(defn- runner
  [config thread-name sweep]
  (let [running (atom true)
        {:keys [interval-ms]} config
        t (doto
            (Thread.
             (fn []
               (while @running
                 (try (sweep config (utility/now))
                      (catch Exception e
                        (log/error e
                                   "Payment sweep threw; continuing"
                                   {:sweep thread-name})))
                 (try (when @running (Thread/sleep (long interval-ms)))
                      (catch InterruptedException _ (reset! running false))))))
            (.setDaemon true)
            (.setName thread-name)
            (.start))]
    {:stop (fn [] (reset! running false) (.interrupt t))}))

(defn start-runner
  [config]
  (runner config "payment-outbound-sweep" sweep-once))

(defn start-transfer-runner
  [config]
  (runner config "payment-transfer-sweep" transfer-sweep-once))
