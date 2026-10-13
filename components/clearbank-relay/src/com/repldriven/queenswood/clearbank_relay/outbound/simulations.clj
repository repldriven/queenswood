(ns com.repldriven.queenswood.clearbank-relay.outbound.simulations
  (:require
    [com.repldriven.queenswood.clearbank-relay.outbound.shared :as shared]

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]

    [com.repldriven.mono.json.interface :as json]))

(defn- simulate
  "Ask the simulator standing in for ClearBank to send a payment in to
  the account's address, which it then reports as it does any other.
  ClearBank itself has no such route, so the call is refused there."
  [config _now intent]
  (let [{:keys! [creditor-bban amount currency reference debtor-name]}
        (shared/context intent)]
    (shared/classify
     (shared/post config
                  "/simulate/inbound-payment"
                  (json/write-str
                   {:bban creditor-bban
                    :amount (.movePointLeft (BigDecimal/valueOf (long amount))
                                            2)
                    :currency currency
                    :reference reference
                    :debtor-name debtor-name})))))

(defn- simulated
  [_config _now _intent _result]
  {:status :outbound-intent-status-settled})

(defn- not-simulated
  [_config _now _intent _failure _reason]
  {:status :outbound-intent-status-failed})

(intent-poller/defoperations
 :clearbank
 {:clearbank-outbound-intent-kind-simulated-payment
  {:call simulate :answered simulated :failed not-simulated}})
