(ns com.repldriven.queenswood.registrar.core
  (:require
    [com.repldriven.queenswood.registrar.subscriptions :as subscriptions]

    [com.repldriven.queenswood.circuit-breaker.interface :as circuit-breaker]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.log.interface :as log]))

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

(defn start
  [adapter config]
  (circuit-breaker/start-probe config
                               (get-in config [:delivery-policy :breaker])
                               (destination adapter)
                               {:probe (fn []
                                         (ensure-subscribed adapter config))
                                :outcome-of (fn [res]
                                              (if (= :failed res)
                                                :failed
                                                :answered))
                                :interval-ms (fn [res]
                                               (if (= :held res)
                                                 (:check-ms config)
                                                 (:retry-ms config)))}))
