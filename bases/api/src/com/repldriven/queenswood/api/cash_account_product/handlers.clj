(ns com.repldriven.queenswood.api.cash-account-product.handlers
  "Product lifecycle writes, performed synchronously rather than sent
  over the bus.

  None of them earns a command. Each writes one product record, nothing
  reacts to any of them, and they arrive over the API rather than an
  unreliable ingress — so the round-trip through a processor bought
  ordering nobody needed and a second failure mode. A retried write is
  read back off the idempotency-key index, which is what actually makes
  these safe to repeat.

  See [ADR-0018](../../../../../../../docs/adr/0018-command-writes-are-earned.md)."
  (:require
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.cash-account-product.interface :as products]

    [com.repldriven.mono.error.interface :as error]))

(defn- config
  [{:keys [record-db record-store]}]
  {:record-db record-db :record-store record-store})

(defn- version-uri
  [{:keys [product-id version-id]}]
  (str "/v1/cash-account-products/" product-id "/versions/" version-id))

(defn- respond
  "One shape for every write: an anomaly becomes its RFC 9457 problem,
  and anything else becomes `success` applied to the record."
  [result success]
  (if (error/anomaly? result)
    (errors/anomaly->response result)
    (success result)))

(defn- created
  [version]
  {:status 201
   :headers {"Location" (version-uri version)}
   :body version})

(defn- ok [version] {:status 200 :body version})

(defn- no-content [_] {:status 204})

(defn- with-idempotency-key
  "The client's key when it sent one. Only create-product reads it, and
  the route doesn't require it, so a create carrying none simply never
  matches the index."
  [data request]
  (let [key (get (:headers request) "idempotency-key")]
    (cond-> data key (assoc :idempotency-key key))))

(defn create-product
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [body]} parameters]
    (respond (products/new-product (config request)
                                   bank-id
                                   (with-idempotency-key body request))
             created)))

(defn open-draft
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path body]} parameters
        {:keys [product-id]} path]
    (respond (products/open-draft (config request) bank-id product-id body)
             created)))

(defn update-draft
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path body]} parameters
        {:keys [product-id version-id]} path]
    (respond (products/update-draft (config request)
                                    bank-id
                                    product-id
                                    version-id
                                    body)
             ok)))

(defn discard-draft
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [product-id version-id]} path]
    (respond (products/discard-draft (config request)
                                     bank-id
                                     product-id
                                     version-id)
             no-content)))

(defn publish-draft
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [product-id version-id]} path]
    (respond (products/publish (config request) bank-id product-id version-id)
             ok)))
