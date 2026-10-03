(ns com.repldriven.queenswood.registrar.core
  (:require
    [com.repldriven.queenswood.registrar.subscriptions :as subscriptions]

    [com.repldriven.queenswood.circuit-breaker.interface :as circuit-breaker]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]))

(def config-schema
  [:map
   [:delivery-policy circuit-breaker/delivery-policy-schema]
   [:retry-ms pos-int?]
   [:check-ms pos-int?]])

(defn- destination [adapter] (str "adapter:" (name adapter)))

(defn ensure-subscribed
  [adapter config]
  (let [{:keys [readiness]} config
        found (subscriptions/subscriptions adapter)]
    (if (error/anomaly? found)
      found
      (let [{:keys [wanted held subscribe]} found
            [outcome result] (held config)]
        (case outcome
          :answered
          (let [outcomes (mapv (fn [subscription]
                                 (log/info "Subscribing at the provider"
                                           {:adapter adapter
                                            :subscription subscription})
                                 (first (subscribe config subscription)))
                               (remove result (wanted config)))]
            (cond
             (every? #{:answered} outcomes)
             (do (reset! readiness true) :held)

             (some #{:retry} outcomes)
             :failed

             :else
             :missing))

          :retry
          :failed

          :missing)))))

(defn- due?
  "Whether the provider is to be asked now: until it holds everything,
  while the adapter's breaker is not closed, so the check is its probe,
  and otherwise once `check-ms` has passed since it last held everything."
  [config destination held-at]
  (let [breaker (circuit-breaker/breaker config destination)]
    (or (nil? held-at)
        (error/anomaly? breaker)
        (and (some? breaker) (not= "closed" (:state breaker)))
        (<= (:check-ms config) (- (utility/now) held-at)))))

(defn start
  [adapter config]
  (let [destination (destination adapter)
        held-at (atom nil)]
    (circuit-breaker/start-probe
     config
     (get-in config [:delivery-policy :breaker])
     destination
     {:probe (fn []
               (let [res (ensure-subscribed adapter config)]
                 (when (= :held res) (reset! held-at (utility/now)))
                 res))
      :outcome-of (fn [res] (if (= :failed res) :failed :answered))
      :interval-ms (fn [_res] (:retry-ms config))
      :due? (fn [] (due? config destination @held-at))})))
