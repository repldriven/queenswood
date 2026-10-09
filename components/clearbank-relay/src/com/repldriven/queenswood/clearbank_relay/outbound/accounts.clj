(ns com.repldriven.queenswood.clearbank-relay.outbound.accounts
  (:require
    [com.repldriven.queenswood.clearbank-relay.outbound.shared :as shared]

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.http-client.interface :as http]))

(defn- classify-account-call
  [res]
  (let [status (:status res)]
    (cond
     (error/anomaly? res)
     [:retry (:message (error/payload res))]

     (not (int? status))
     [:retry "no HTTP status"]

     (or (<= 500 status) (= 408 status) (= 429 status))
     [:retry (str "HTTP " status)]

     (<= 400 status 499)
     [:refused (or (:detail (http/res->edn res)) (str "HTTP " status))]

     (<= 200 status 299)
     [:answered (http/res->edn res)]

     :else
     [:retry (str "HTTP " status)])))

(defn- addresses
  [body]
  (let [{:keys [sortCode accountNumber]} body]
    [{:scheme "scan" :sort-code sortCode :account-number accountNumber}]))

(defn- open
  [config _now intent]
  (classify-account-call
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
    (classify-account-call
     (shared/post config
                  (str "/v1/virtual-accounts/" provider-account-id "/close")
                  (:request intent)))))

(defn- closed
  [_config _now intent _body]
  (let [{:keys! [bank-id account-id]} (shared/context intent)]
    {:status :outbound-intent-status-settled
     :event (shared/account-event intent
                                  "payment-account-closed"
                                  {:bank-id bank-id :account-id account-id})}))

(defn- close-failed
  [_config _now intent failure reason]
  (let [{:keys! [bank-id account-id]} (shared/context intent)]
    {:status :outbound-intent-status-failed
     :event (shared/account-event intent
                                  "payment-account-close-refused"
                                  {:bank-id bank-id
                                   :account-id account-id
                                   :reason (shared/undelivered failure
                                                               reason)})}))

(defn- reissue
  [config _now intent]
  ;; nosemgrep: unchecked-intent-data — optional in a reissue's context
  (let [{:keys [provider-account-id]} (shared/context intent)
        path (if provider-account-id
               (str "/v1/virtual-accounts/" provider-account-id "/reissue")
               "/v1/virtual-accounts")]
    (classify-account-call (shared/post config path (:request intent)))))

(defn- reissued
  [_config _now intent body]
  (let [{:keys! [bank-id account-id rotation-key]} (shared/context intent)]
    {:status :outbound-intent-status-settled
     :event (shared/account-event intent
                                  "payment-address-reissued"
                                  {:bank-id bank-id
                                   :account-id account-id
                                   :provider-account-id (:id body)
                                   :rotation-key rotation-key
                                   :addresses (addresses body)})}))

(defn- reissue-failed
  [_config _now intent failure reason]
  (let [{:keys! [bank-id account-id rotation-key]} (shared/context intent)]
    {:status :outbound-intent-status-failed
     :event (shared/account-event intent
                                  "payment-address-reissue-failed"
                                  {:bank-id bank-id
                                   :account-id account-id
                                   :rotation-key rotation-key
                                   :reason (shared/undelivered failure
                                                               reason)})}))

(intent-poller/defoperations
 :clearbank
 {:clearbank-outbound-intent-kind-open-account
  {:call open :answered opened :failed open-failed}
  :clearbank-outbound-intent-kind-close-account
  {:call close :answered closed :failed close-failed}
  :clearbank-outbound-intent-kind-reissue-address
  {:call reissue :answered reissued :failed reissue-failed}})
