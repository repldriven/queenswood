(ns com.repldriven.queenswood.changelog-relay.runner
  (:require
    ;; nosemgrep: fdb-outside-store — the relay owns no store, it tails one
    [com.repldriven.queenswood.fdb.interface :as fdb]

    [com.repldriven.mono.log.interface :as log]))

(def ^:private default-poll-ms 100)

(defn start
  [config]
  (let [{:keys [record-db consumer-id store-name handler poll-ms
                keyspace-prefix]}
        config
        interval (or poll-ms default-poll-ms)
        running (atom true)
        t (doto
            (Thread.
             (fn []
               (while @running
                 (let [relayed (try
                                 (fdb/process-changelog
                                  record-db
                                  consumer-id
                                  store-name
                                  handler
                                  {:deduplicate? false
                                   :keyspace-prefix keyspace-prefix})
                                 (catch Exception e
                                   (log/error
                                    e
                                    "Changelog relay pass failed; will redrive"
                                    {:consumer-id consumer-id
                                     :store-name store-name})
                                   0))]
                   (when-not (pos? relayed)
                     (try (when @running (Thread/sleep interval))
                          (catch InterruptedException _
                            (reset! running false))))))))
            (.setDaemon true)
            (.setName (str consumer-id "-relay"))
            (.start))]
    {:stop (fn [] (reset! running false) (.interrupt t) (.join t 5000))}))
