(ns com.repldriven.queenswood.payment.sweep
  (:require
    [com.repldriven.queenswood.payment.domain.outbound :as outbound]

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
                  stuck
                  (outbound/stuck-outbound (into pending held) now config)]
                 (doseq [payment stuck]
                   (log/error "Outbound payment stuck" payment))
                 stuck)]
    (when (error/anomaly? result)
      (log/error "Outbound sweep failed to read payments" result))
    result))

(defn start-runner
  [config]
  (let [running (atom true)
        {:keys [interval-ms]} config
        t (doto
            (Thread.
             (fn []
               (while @running
                 (try (sweep-once config (utility/now))
                      (catch Exception e
                        (log/error e
                                   "Payment outbound sweep threw; continuing")))
                 (try (when @running (Thread/sleep (long interval-ms)))
                      (catch InterruptedException _ (reset! running false))))))
            (.setDaemon true)
            (.setName "payment-outbound-sweep")
            (.start))]
    {:stop (fn [] (reset! running false) (.interrupt t))}))
