(ns com.repldriven.queenswood.clearbank-simulator.simulate.handlers
  (:require
    [com.repldriven.queenswood.clearbank-simulator.webhook
     :as webhook]

    [com.repldriven.mono.utility.interface :refer [uuidv7]]))

;; ClearBank's documented sandbox trigger: an inbound whose debtor Name is
;; this value is held for screening (InboundHeldTransaction). The sim then
;; auto-resolves it per the request `outcome` ("return" → declined, else
;; released → settled).
(def ^:private held-magic-name "6a41a29eafcf455493")

(defn inbound-payment
  [_config]
  (fn [request]
    (let [{:keys [webhooks sort-code webhook-delay-ms parameters]} request
          {:keys [body]} parameters
          {:keys [debtor-name outcome]} body
          e2e-id (str (uuidv7))
          config {:webhooks webhooks}]
      (future
       (if (= held-magic-name debtor-name)
         (do
           (webhook/fire-inbound-held-transaction config sort-code e2e-id body)
           (when (pos? (or webhook-delay-ms 0))
             (Thread/sleep webhook-delay-ms))
           (if (= "return" outcome)
             (webhook/fire-inbound-transaction-returned config
                                                        sort-code
                                                        e2e-id
                                                        body)
             (webhook/fire-transaction-settled config
                                               sort-code
                                               e2e-id
                                               :credit
                                               body)))
         (webhook/fire-transaction-settled config
                                           sort-code
                                           e2e-id
                                           :credit
                                           body)))
      {:status 202
       :body {:endToEndIdentification e2e-id}})))

(defn inbound-cop-request
  [_config]
  (fn [request]
    (let [{:keys [webhooks parameters]} request
          {:keys [body]} parameters
          {:keys [accountDetails]} body
          {:keys [sortCode]} accountDetails
          request-id (str "cop-" (uuidv7))
          config {:webhooks webhooks}]
      (future
       (webhook/fire-inbound-cop-request config
                                         sortCode
                                         request-id
                                         body))
      {:status 202
       :body {:requestId request-id}})))
