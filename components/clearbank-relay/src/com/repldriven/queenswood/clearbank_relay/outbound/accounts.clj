(ns com.repldriven.queenswood.clearbank-relay.outbound.accounts
  (:require
    [com.repldriven.queenswood.clearbank-relay.outbound.shared :as shared]

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]))

(defn- addresses
  [body]
  (let [{:keys [sortCode accountNumber]} body]
    [{:scheme "scan" :sort-code sortCode :account-number accountNumber}]))

(defn- open
  [config _now intent]
  (shared/classify
   (shared/post config "/v1/virtual-accounts" (:request intent))))

(defn- opened
  [_config _now intent body]
  (let [{:keys! [bank-id account-id]} (shared/context intent)]
    {:status :outbound-intent-status-settled
     :event (shared/account-event intent
                                  "payment-account-opened"
                                  {:bank-id bank-id
                                   :account-id account-id
                                   :provider-account-id (:id body)
                                   :addresses (addresses body)})}))

(defn- open-failed
  [_config _now intent failure reason]
  (let [{:keys! [bank-id account-id]} (shared/context intent)]
    {:status :outbound-intent-status-failed
     :event (shared/account-event intent
                                  "payment-account-open-refused"
                                  {:bank-id bank-id
                                   :account-id account-id
                                   :reason (shared/undelivered failure
                                                               reason)})}))

(defn- close
  [config _now intent]
  (let [{:keys! [provider-account-id]} (shared/context intent)]
    (shared/classify
     (shared/post config
                  (str "/v1/virtual-accounts/" provider-account-id "/close")
                  (:request intent)))))

(defn- closed
  [_config _now intent _body]
  (let [{:keys! [bank-id account-id operation-id]} (shared/context intent)]
    {:status :outbound-intent-status-settled
     :event (shared/account-event intent
                                  "payment-account-closed"
                                  {:bank-id bank-id
                                   :account-id account-id
                                   :operation-id operation-id})}))

(defn- close-failed
  [_config _now intent failure reason]
  (let [{:keys! [bank-id account-id operation-id]} (shared/context intent)]
    {:status :outbound-intent-status-failed
     :event (shared/account-event intent
                                  "payment-account-close-refused"
                                  {:bank-id bank-id
                                   :account-id account-id
                                   :operation-id operation-id
                                   :reason (shared/undelivered failure
                                                               reason)})}))

(defn- operate
  "A block or an unblock, which a ClearBank virtual account has no
  reversible state for: the platform holds an inbound payment to a
  suspended account and submits none from one, so there is nothing to
  call."
  [_config _now _intent]
  [:answered nil])

(defn- operated
  [_config _now intent _body]
  (let [{:keys! [bank-id account-id operation-id]} (shared/context intent)]
    {:status :outbound-intent-status-settled
     :event (shared/account-event intent
                                  "payment-account-operation-completed"
                                  {:bank-id bank-id
                                   :account-id account-id
                                   :operation-id operation-id})}))

(defn- operation-failed
  [_config _now intent failure reason]
  (let [{:keys! [bank-id account-id operation-id]} (shared/context intent)]
    {:status :outbound-intent-status-failed
     :event (shared/account-event intent
                                  "payment-account-operation-refused"
                                  {:bank-id bank-id
                                   :account-id account-id
                                   :operation-id operation-id
                                   :reason (shared/undelivered failure
                                                               reason)})}))

(defn- reissue
  [config _now intent]
  ;; nosemgrep: unchecked-intent-data — optional in a reissue's context
  (let [{:keys [provider-account-id]} (shared/context intent)
        path (if provider-account-id
               (str "/v1/virtual-accounts/" provider-account-id "/reissue")
               "/v1/virtual-accounts")]
    (shared/classify (shared/post config path (:request intent)))))

(defn- reissued
  [_config _now intent body]
  (let [{:keys! [bank-id account-id operation-id]} (shared/context intent)]
    {:status :outbound-intent-status-settled
     :event (shared/account-event intent
                                  "payment-address-reissued"
                                  {:bank-id bank-id
                                   :account-id account-id
                                   :provider-account-id (:id body)
                                   :operation-id operation-id
                                   :addresses (addresses body)})}))

(defn- reissue-failed
  [_config _now intent failure reason]
  (let [{:keys! [bank-id account-id operation-id]} (shared/context intent)]
    {:status :outbound-intent-status-failed
     :event (shared/account-event intent
                                  "payment-address-reissue-failed"
                                  {:bank-id bank-id
                                   :account-id account-id
                                   :operation-id operation-id
                                   :reason (shared/undelivered failure
                                                               reason)})}))

(intent-poller/defoperations
 :clearbank
 {:clearbank-outbound-intent-kind-open-account
  {:call open :answered opened :failed open-failed}
  :clearbank-outbound-intent-kind-close-account
  {:call close :answered closed :failed close-failed}
  :clearbank-outbound-intent-kind-reissue-address
  {:call reissue :answered reissued :failed reissue-failed}
  :clearbank-outbound-intent-kind-account-operation
  {:call operate :answered operated :failed operation-failed}})
