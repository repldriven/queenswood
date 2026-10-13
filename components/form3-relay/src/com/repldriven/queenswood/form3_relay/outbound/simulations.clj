(ns com.repldriven.queenswood.form3-relay.outbound.simulations
  (:require
    [com.repldriven.queenswood.form3-relay.outbound.shared :as shared]

    [com.repldriven.queenswood.form3-relay.form3 :as form3]

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]))

(defn- simulate
  "Ask the simulator standing in for Form3 to send a payment in to the
  account's address, which it then reports as it does any other. Form3
  itself has no such route, so the call is refused there."
  [config _now intent]
  (let [{:keys! [creditor-bban amount currency reference debtor-name]}
        (shared/context intent)]
    (shared/answer
     (shared/call config
                  {:method :post
                   :path "/simulate/inbound-payment"
                   :body {:bban creditor-bban
                          :amount (bigdec (form3/->major-units amount))
                          :currency currency
                          :reference reference
                          :debtor-name debtor-name}}))))

(defn- simulated
  [_config _now _intent _result]
  {:status :outbound-intent-status-settled})

(defn- not-simulated
  [_config _now _intent _failure _reason]
  {:status :outbound-intent-status-failed})

(intent-poller/defoperations
 :form3
 {:form3-outbound-intent-kind-simulated-payment
  {:call simulate :answered simulated :failed not-simulated}})
