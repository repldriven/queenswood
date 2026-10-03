(ns com.repldriven.queenswood.clearbank-relay.outbound
  (:require
    [com.repldriven.queenswood.clearbank-relay.store :as store]

    [com.repldriven.queenswood.clearbank-webhook.interface :as
     clearbank-webhook]
    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.http-client.interface :as http]

    [clojure.edn :as edn]))

(defn- post-signed
  "POST a signed request to the provider. An unreachable provider is
  `:payment/unavailable` — the kind names the domain, not the vendor,
  because a second scheme provider consuming this channel must not
  change what the failure is called (ADR-0020)."
  [url signing-key request-body]
  (error/try-nom
   :payment/unavailable
   "Failed to POST outbound payment to the scheme adapter"
   (let [res (let-nom> [signature (clearbank-webhook/sign
                                   (:private-key signing-key)
                                   request-body)]
               (http/request {:method :post
                              :url url
                              :headers {"Content-Type" "application/json"
                                        clearbank-webhook/signature-header
                                        signature}
                              :body request-body}))]
     (if (error/anomaly? res)
       (error/fail :payment/unavailable
                   {:message "Scheme adapter unreachable"
                    :cause res})
       res))))

(defn- transaction-response
  [res end-to-end-id]
  (let [body (http/res->edn res)]
    (when (map? body)
      (some (fn [{:keys [endToEndIdentification response]}]
              (when (= end-to-end-id endToEndIdentification) response))
            (:transactions body)))))

(defn- classify
  [res end-to-end-id]
  (let [status (:status res)]
    (cond
     (error/anomaly? res)
     [:retry (:message (error/payload res))]

     (not (int? status))
     [:retry "no HTTP status"]

     (or (<= 500 status) (= 408 status) (= 429 status))
     [:retry (str "HTTP " status)]

     (<= 400 status 499)
     [:refused (str "HTTP " status)]

     (<= 200 status 299)
     (let [response (transaction-response res end-to-end-id)]
       (cond
        (= "Accepted" response)
        [:answered nil]

        (some? response)
        [:refused (str "HTTP " status " response " response)]

        :else
        [:refused
         (str "HTTP " status
              " with no response for "
              end-to-end-id)]))

     :else
     [:retry (str "HTTP " status)])))

(defn- context
  [intent]
  (or (some-> (not-empty (:context intent))
              edn/read-string)
      {}))

(defn- post
  [config path request]
  (let [{:keys [clearbank-url signing-key post-fn]} config]
    ((or post-fn post-signed) (str clearbank-url path) signing-key request)))

(defn- undelivered
  [failure reason]
  (if (= :undelivered failure) (str "Undelivered: " reason) reason))

;; ---- payments

(defn- rejected
  [intent failure-kind reason now]
  {:event-name "transaction-rejected"
   :dedup-key (str (:dedup-key intent) ":submission-rejected")
   :data {:end-to-end-id (:dedup-key intent)
          :scheme "fps"
          :debit-credit-code :debit-credit-code-debit
          :cancellation-code "NARR"
          :failure-kind failure-kind
          :reason-code "NARR"
          :cancellation-reason reason
          :is-return false
          :timestamp-rejected now}})

(defn- pay
  "The outbound FPS call. A retried POST is safe — ClearBank dedupes on
  endToEndIdentification — which is what lets the poller retry at all."
  [config _now intent]
  (let [{:keys [dedup-key request]} intent]
    (classify (post config "/v3/payments/fps" request) dedup-key)))

(defn- paid
  "An accepted submission leaves the payment sent, for the settlement
  webhook to complete."
  [_config _now _intent _result]
  {:status "sent"})

(defn- payment-failed
  [_config now intent failure reason]
  (rejected intent
            (if (= :refused failure)
              :failure-kind-refused
              :failure-kind-undelivered)
            reason
            now))

;; ---- accounts

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

(defn- account-event
  [intent event-name data]
  {:event-name event-name
   :dedup-key (str (:dedup-key intent) ":" event-name)
   :data data})

(defn- addresses
  [body]
  (let [{:keys [sortCode accountNumber]} body]
    [{:scheme "scan" :sort-code sortCode :account-number accountNumber}]))

(defn- open
  [config _now intent]
  (classify-account-call
   (post config "/v1/virtual-accounts" (:request intent))))

(defn- opened
  [_config _now intent body]
  (let [{:keys! [bank-id account-id]} (context intent)]
    {:status "settled"
     :event (account-event intent
                           "payment-account-opened"
                           {:bank-id bank-id
                            :account-id account-id
                            :provider-account-id (:id body)
                            :addresses (addresses body)})}))

(defn- open-failed
  [_config _now intent failure reason]
  (let [{:keys! [bank-id account-id]} (context intent)]
    (account-event intent
                   "payment-account-refused"
                   {:bank-id bank-id
                    :account-id account-id
                    :reason (undelivered failure reason)})))

(defn- close
  [config _now intent]
  (let [{:keys! [provider-account-id]} (context intent)]
    (classify-account-call
     (post config
           (str "/v1/virtual-accounts/" provider-account-id "/close")
           (:request intent)))))

(defn- closed
  [_config _now intent _body]
  (let [{:keys! [bank-id account-id]} (context intent)]
    {:status "settled"
     :event (account-event intent
                           "payment-account-closed"
                           {:bank-id bank-id :account-id account-id})}))

(defn- close-failed
  [_config _now intent failure reason]
  (let [{:keys! [bank-id account-id]} (context intent)]
    (account-event intent
                   "payment-account-close-refused"
                   {:bank-id bank-id
                    :account-id account-id
                    :reason (undelivered failure reason)})))

(defn- reissue
  [config _now intent]
  ;; nosemgrep: unchecked-intent-data — optional in a reissue's context
  (let [{:keys [provider-account-id]} (context intent)
        path (if provider-account-id
               (str "/v1/virtual-accounts/" provider-account-id "/reissue")
               "/v1/virtual-accounts")]
    (classify-account-call (post config path (:request intent)))))

(defn- reissued
  [_config _now intent body]
  (let [{:keys! [bank-id account-id rotation-key]} (context intent)]
    {:status "settled"
     :event (account-event intent
                           "payment-address-reissued"
                           {:bank-id bank-id
                            :account-id account-id
                            :provider-account-id (:id body)
                            :rotation-key rotation-key
                            :addresses (addresses body)})}))

(defn- reissue-failed
  [_config _now intent failure reason]
  (let [{:keys! [bank-id account-id rotation-key]} (context intent)]
    (account-event intent
                   "payment-address-reissue-failed"
                   {:bank-id bank-id
                    :account-id account-id
                    :rotation-key rotation-key
                    :reason (undelivered failure reason)})))

(intent-poller/defoperations
 :clearbank
 {"payment" {:call pay :answered paid :failed payment-failed}
  "open-account" {:call open :answered opened :failed open-failed}
  "close-account" {:call close :answered closed :failed close-failed}
  "reissue-address" {:call reissue :answered reissued :failed reissue-failed}})

(defn- poller-config
  [config]
  (assoc config
         :adapter :clearbank
         :store store/spec
         :default-operation "payment"))

(defn drain-once
  "Make each due pending call once, oldest first, holding a call for an
  account while an earlier one for it is unsent. Reads are
  transactional; the HTTP call and status write per intent are
  separate, so no network I/O happens inside an FDB transaction."
  [config now]
  (intent-poller/drain-once (poller-config config) now))

(defn start-runner
  "Start the daemon poll loop that drains pending outbound intents.
  Returns `{:stop fn}`."
  [config]
  (intent-poller/start (poller-config config)))
