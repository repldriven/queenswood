(ns com.repldriven.queenswood.api.webhook.handlers
  "Endpoint lifecycle and delivery writes, performed synchronously
  rather than sent over the bus.

  None of them earns a command. Each writes one endpoint or one
  delivery, nothing reacts to any of them, and they arrive over the API
  rather than an unreliable ingress. The unique `[bank-id,
  idempotency-key]` index is what makes a retried registration safe to
  repeat, and the rotation's key on the record does the same for a
  retried rotation.

  See [ADR-0018](../../../../../../../docs/adr/0018-command-writes-are-earned.md)."
  (:require
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.webhook.interface :as webhook]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- config
  [{:keys [record-db record-store]}]
  {:record-db record-db :record-store record-store})

(defn- endpoint-uri
  [{:keys [endpoint-id]}]
  (str "/v1/webhook-endpoints/" endpoint-id))

(defn- delivery-uri
  [{:keys [endpoint-id delivery-id]}]
  (str "/v1/webhook-endpoints/" endpoint-id "/deliveries/" delivery-id))

(defn- respond
  "One shape for every write: an anomaly becomes its RFC 9457 problem,
  and anything else becomes `success` applied to the record."
  [result success]
  (if (error/anomaly? result)
    (errors/anomaly->response result)
    (success result)))

(defn- registered
  "The one response that carries the secret, beside the endpoint as
  every read route returns it. `->body` declares no secret, so the
  secret reaches the caller only because this names it."
  [endpoint]
  {:status 201
   :headers {"Location" (endpoint-uri endpoint)}
   :body {:endpoint (webhook/->body endpoint) :secret (:secret endpoint)}})

(defn- rotated
  [endpoint]
  {:status 200
   :body {:endpoint (webhook/->body endpoint)
          :secret (:secret endpoint)
          :previous-secret-expires-at (:previous-secret-expires-at endpoint)}})

(defn- ok [endpoint] {:status 200 :body (webhook/->body endpoint)})

(defn- no-content [_] {:status 204})

(defn- created-delivery
  [delivery]
  {:status 201
   :headers {"Location" (delivery-uri delivery)}
   :body (webhook/->delivery-body delivery)})

(defn- resent
  [{:keys [deliveries]}]
  {:status 200 :body {:items (mapv webhook/->delivery-body deliveries)}})

(defn- with-idempotency-key
  "The client's key when it sent one. Every route that reads it
  requires the header via `server/require-idempotency-key`, so this
  lifts it onto the write's data."
  [data request]
  (let [key (get (:headers request) "idempotency-key")]
    (cond-> data key (assoc :idempotency-key key))))

(defn register
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [body]} parameters]
    (respond (webhook/register (config request)
                               bank-id
                               (with-idempotency-key body request))
             registered)))

(defn update-endpoint
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path body]} parameters]
    (respond (webhook/update-endpoint (config request)
                                      bank-id
                                      (:endpoint-id path)
                                      body)
             ok)))

(defn enable
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path body]} parameters]
    (respond (webhook/enable (config request)
                             bank-id
                             (:endpoint-id path)
                             (utility/assoc-some {} :since (:since body)))
             ok)))

(defn disable
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters]
    (respond (webhook/disable (config request) bank-id (:endpoint-id path))
             ok)))

(defn remove-endpoint
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters]
    (respond (webhook/remove-endpoint (config request)
                                      bank-id
                                      (:endpoint-id path))
             no-content)))

(defn rotate-secret
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters]
    (respond (webhook/rotate-secret (config request)
                                    bank-id
                                    (:endpoint-id path)
                                    (with-idempotency-key {} request))
             rotated)))

(defn test-notification
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters]
    (respond (webhook/test-notification (config request)
                                        bank-id
                                        (:endpoint-id path))
             created-delivery)))

(defn resend
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters]
    (respond (webhook/resend (config request)
                             bank-id
                             (:endpoint-id path)
                             (:delivery-id path))
             created-delivery)))

(defn resend-window
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path body]} parameters]
    (respond (webhook/resend-window (config request)
                                    bank-id
                                    (:endpoint-id path)
                                    body)
             resent)))
