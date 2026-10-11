(ns com.repldriven.queenswood.access-log.writer
  (:require
    [com.repldriven.queenswood.access-log.store :as store]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.telemetry.interface :as telemetry])
  (:import
    (java.util ArrayList)
    (java.util.concurrent ArrayBlockingQueue)))

(defn offer
  [writer entry]
  (let [{:keys [^ArrayBlockingQueue queue dropped dropped-count]} writer]
    (when-not (.offer queue entry)
      (swap! dropped-count inc)
      (telemetry/inc-counter! dropped {}))
    nil))

(defn dropped
  [writer]
  @(:dropped-count writer))

(defn- write-batch
  [writer batch]
  (let [{:keys [config written dropped dropped-count]} writer
        result (store/append-all (select-keys config [:record-db :record-store])
                                 batch)]
    (if (error/anomaly? result)
      (do (log/error "Access entries not written; dropped"
                     {:count (count batch)
                      :message (:message (error/payload result))})
          (swap! dropped-count + (count batch))
          (telemetry/add-counter! dropped (count batch) {}))
      (doseq [[bank-id entries] (group-by :bank-id batch)]
        (telemetry/add-counter! written (count entries) {:bank-id bank-id})))))

(defn- drain
  [writer]
  (let [{:keys [^ArrayBlockingQueue queue config]} writer
        batch-size (:batch-size config)]
    (loop []
      (let [batch (ArrayList.)]
        (.drainTo queue batch (int batch-size))
        (when (seq batch)
          (write-batch writer (vec batch))
          (recur))))))

(defn start
  [config]
  (let [{:keys [capacity flush-ms telemetry]} config
        queue (ArrayBlockingQueue. (int capacity))
        running (atom true)
        writer {:config config
                :queue queue
                :dropped-count (atom 0)
                :written (telemetry/counter {:name "access_log.entries.written"
                                             :description
                                             "Access entries written, by bank"
                                             :otel telemetry})
                :dropped
                (telemetry/counter
                 {:name "access_log.entries.dropped"
                  :description
                  "Access entries the full queue refused or a failed write lost"
                  :otel telemetry})
                :depth (telemetry/gauge {:name "access_log.queue.depth"
                                         :description
                                         "Access entries waiting to be written"
                                         :observe (fn [] (.size queue))
                                         :otel telemetry})}
        thread (doto (Thread.
                      (fn []
                        (while @running
                          (try (Thread/sleep (long flush-ms))
                               (catch InterruptedException _
                                 (reset! running false)))
                          (try (drain writer)
                               (catch Exception e
                                 (log/error
                                  e
                                  "Access entries drain threw; continuing")))))
                      "access-log-writer")
                 (.setDaemon true)
                 (.start))]
    (assoc writer
           :stop
           (fn []
             (reset! running false)
             (.interrupt thread)
             (.join thread 5000)
             (drain writer)
             (telemetry/close-instrument (:depth writer))))))
