(ns com.repldriven.queenswood.modulr-relay.outbound.simulations
  (:require
    [com.repldriven.queenswood.modulr-relay.outbound.shared :as shared]

    [com.repldriven.queenswood.modulr-relay.modulr :as modulr]

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]

    [com.repldriven.mono.json.interface :as json]))

(defn- credit-body
  [config intent]
  (let [{:keys! [account-id amount reference debtor-name]
         :keys [provider-account-id]}
        (shared/context intent)
        held (shared/held-at config account-id provider-account-id)]
    (when held
      (json/write-str {:accountId held
                       :amount (modulr/->major-units amount)
                       :description reference
                       :type "PI_FAST"
                       :payerDetail {:name debtor-name
                                     :identifier shared/sandbox-identifier}}))))

(defn- simulate
  "Credit the account from outside through Modulr's sandbox, which then
  reports the payment in as it does any other. One whose provider
  account is not open yet waits for it."
  [config _now intent]
  (if-let [body (credit-body config intent)]
    (shared/answer
     (shared/call config intent {:method :post :path "/credit" :raw-body body}))
    [:wait "No provider account holds it yet"]))

(defn- simulated
  [_config _now _intent _result]
  {:status :outbound-intent-status-settled})

(defn- not-simulated
  [_config _now _intent _failure _reason]
  {:status :outbound-intent-status-failed})

(intent-poller/defoperations
 :modulr
 {:modulr-outbound-intent-kind-simulated-payment
  {:call simulate :answered simulated :failed not-simulated}})
