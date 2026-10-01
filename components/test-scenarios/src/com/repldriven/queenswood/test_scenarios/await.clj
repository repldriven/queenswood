(ns com.repldriven.queenswood.test-scenarios.await
  (:require
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

(def default-timeout-ms 20000)

(def ^:private interval-ms 25)

(defn until
  [{:keys [await-timeout-ms]} f done?]
  (let [deadline (+ (utility/now) (or await-timeout-ms default-timeout-ms))]
    (loop []
      (let [value (f)]
        (cond
         (done? value)
         {:done? true :value value}

         (>= (utility/now) deadline)
         {:done? false :value value}

         :else
         (do (Thread/sleep ^long interval-ms) (recur)))))))

(defn value
  [ctx what f done?]
  (let [{:keys [done? value]} (until ctx f done?)]
    (if done?
      value
      (error/fail :scenario/timed-out
                  {:message (str "Timed out waiting for " what)
                   :last-seen (pr-str value)}))))

(defn timed-out?
  [x]
  (and (error/anomaly? x) (= :scenario/timed-out (error/kind x))))
