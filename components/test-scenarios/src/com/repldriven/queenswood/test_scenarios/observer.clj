(ns com.repldriven.queenswood.test-scenarios.observer
  (:require
    [com.repldriven.mono.kafka.interface :as kafka]

    [clojure.core.async :as async]))

(def ^:private poll-timeout-ms 500)

(defn start
  [consumer]
  (let [records (atom [])
        {:keys [c stop]} (kafka/receive consumer poll-timeout-ms)]
    (async/thread (loop []
                    (when-let [{:keys [data]} (async/<!! c)]
                      (swap! records conj data)
                      (recur))))
    {:records records :stop stop}))

(defn records
  [observer]
  (if observer @(:records observer) []))

(defn stop
  [{:keys [stop]}]
  (when stop (async/put! stop :stop))
  nil)
