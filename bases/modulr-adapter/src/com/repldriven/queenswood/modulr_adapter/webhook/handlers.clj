(ns com.repldriven.queenswood.modulr-adapter.webhook.handlers
  (:require
    [com.repldriven.queenswood.modulr-adapter.publisher :as publisher]

    [com.repldriven.queenswood.modulr-relay.interface :as relay]
    [com.repldriven.queenswood.modulr-webhook.interface :as modulr-webhook]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.edn :as edn]
    [clojure.string :as str]))

(def ^:private move-prefix "move-")

(defn- verified
  [handler]
  (fn [request]
    (let [{:keys [webhook-credentials headers uri]} request
          res (modulr-webhook/verify (dissoc webhook-credentials :key-id)
                                     headers
                                     (utility/now))]
      (if (error/anomaly? res)
        (let [kind (error/kind res)]
          (log/warn "Modulr notification refused" {:uri uri :kind kind})
          {:status 401
           :body {:type (str kind)
                  :title "UNAUTHORIZED"
                  :status 401
                  :detail (:message (error/payload res))}})
        (handler request)))))

(defn- fdb
  [request]
  (select-keys request [:record-db :record-store]))

(defn- intent
  [request reference]
  (when-let [id (relay/reference->id reference)]
    (let [found (relay/find-intent (fdb request) id)]
      (when (and found (not (error/anomaly? found)))
        (update found
                :context
                (fn [c]
                  (or (some-> (not-empty c)
                              edn/read-string)
                      {})))))))

(defn- persist-one
  [request {:keys [event-name dedup-key data]} settles]
  (let [schema (get (:avro request) event-name)]
    (let-nom> [payload (avro/serialize schema data)]
      (let [res (relay/save-event (fdb request)
                                  {:outbox-id (str (utility/uuidv7))
                                   :dedup-key dedup-key
                                   :event-name event-name
                                   :payload payload
                                   :correlation-id (str (utility/uuidv7))
                                   :causation-id (str (utility/uuidv7))
                                   :created-at (utility/now)}
                                  settles)]
        (if (relay/uniqueness-violation? res) :ok res)))))

(defn- respond
  "200 once every event is recorded, or with nothing to record; 400 with
  nothing written where the notification could not be mapped; 500
  otherwise, so Modulr delivers it again."
  [request what descriptors settles]
  (cond
   (error/rejection? descriptors)
   (let [{:keys [message] :as payload} (error/payload descriptors)]
     (log/error (str "Refused " what " notification") payload)
     {:status 400
      :body {:type (str (error/kind descriptors))
             :title "REJECTED"
             :status 400
             :detail message}})

   (error/anomaly? descriptors)
   (do (log/error (str "Failed to read " what " notification") descriptors)
       {:status 500 :body {:error "notification not recorded"}})

   :else
   (let [result (reduce (fn [_ d]
                          (let [res (persist-one request d settles)]
                            (if (error/anomaly? res) (reduced res) :ok)))
                        :ok
                        descriptors)]
     (if (error/anomaly? result)
       (do (log/error (str "Failed to record " what " notification") result)
           {:status 500 :body {:error "notification not recorded"}})
       {:status 200 :body {}}))))

(defn- own?
  "True for a PAYIN the adapter caused itself — the far side of a
  transfer between accounts, a reissue's move, or a sandbox credit — which
  the ledger already has."
  [request payin]
  (let [{:keys [SourceExternalReference PaymentReference]} payin]
    (or (str/starts-with? (or SourceExternalReference "") move-prefix)
        (contains? #{"transfer" "credit"}
                   (:kind (intent request SourceExternalReference)))
        (= "credit" (:kind (intent request PaymentReference))))))

(def payin
  (verified (fn [request]
              (let [body (get-in request [:parameters :body])
                    {:keys [PaymentId Type]} body]
                (log/info "PAYIN notification received"
                          {:payment-id PaymentId :type Type})
                (cond
                 (own? request body)
                 {:status 200 :body {}}

                 (= "PO_REV" Type)
                 (do (log/error "A returned outbound payment arrived unmapped"
                                {:payment-id PaymentId})
                     {:status 200 :body {}})

                 :else
                 (respond request "PAYIN" (publisher/inbound body) nil))))))

(def payout
  (verified (fn [request]
              (let [body (get-in request [:parameters :body])
                    {:keys [PaymentId Status ExternalReference]} body]
                (log/info "PAYOUT notification received"
                          {:payment-id PaymentId :status Status})
                (if (or (str/blank? ExternalReference)
                        (str/starts-with? ExternalReference move-prefix))
                  {:status 200 :body {}}
                  (let [found (intent request ExternalReference)]
                    (respond request
                             "PAYOUT"
                             (publisher/payout body found)
                             (:dedup-key found))))))))

(defn- provider-payment
  [request payment-id]
  (let [res (relay/request (select-keys request [:modulr-url :credentials])
                           {:method :get
                            :path "/payments"
                            :query {"id" payment-id}})
        [outcome result] (relay/classify res)]
    (if (= :ok outcome)
      (first (:content result))
      (error/fail :payment/unavailable
                  {:message "The payment the notification names is unreadable"
                   :payment-id payment-id
                   :reason result}))))

(def compliance
  (verified (fn [request]
              (let [body (get-in request [:parameters :body])
                    {:keys [PaymentBid ComplianceStatus]} body]
                (log/info "PAYMENTCOMPLIANCESTATUS notification received"
                          {:payment-id PaymentBid :status ComplianceStatus})
                (respond request
                         "PAYMENTCOMPLIANCESTATUS"
                         (let-nom> [payment (provider-payment request
                                                              PaymentBid)]
                           (publisher/compliance body payment))
                         nil)))))
