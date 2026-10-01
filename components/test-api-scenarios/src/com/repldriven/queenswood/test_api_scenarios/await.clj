(ns com.repldriven.queenswood.test-api-scenarios.await
  (:require
    [com.repldriven.mono.utility.interface :as utility]))

(def default-timeout-ms 15000)

(def default-interval-ms 50)

(defn until
  [{:keys [timeout-ms interval-ms]} f done?]
  (let [deadline (+ (utility/now) (or timeout-ms default-timeout-ms))
        interval (or interval-ms default-interval-ms)]
    (loop []
      (let [value (f)]
        (cond
         (done? value)
         {:done? true :value value}

         (>= (utility/now) deadline)
         {:done? false :value value}

         :else
         (do (Thread/sleep ^long interval) (recur)))))))
