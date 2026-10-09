(ns com.repldriven.queenswood.api.cash-account-migration.handlers
  (:require
    [com.repldriven.queenswood.api.errors :as errors]
    [com.repldriven.queenswood.api.shared.actor :as shared.actor]

    [com.repldriven.queenswood.cash-account-migration-api.interface :as
     migration-api]
    [com.repldriven.queenswood.cash-account-migration.interface :as migrations]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- config
  [{:keys [record-db record-store]}]
  {:record-db record-db :record-store record-store})

(defn- migration-uri
  [{:keys [migration-id]}]
  (str "/v1/cash-account-migrations/" migration-id))

(defn create-migration
  "Author a migration. Writes synchronously rather than over the bus:
  the write spans one record, nothing reacts to it, and it does not
  arrive over an unreliable ingress, so it earns no command."
  [request]
  (let [{:keys [auth parameters headers]} request
        {:keys [bank-id]} auth
        {:keys [body]} parameters
        data (assoc body
                    :bank-id bank-id
                    :idempotency-key (get headers "idempotency-key")
                    :created-by (shared.actor/actor auth))
        result (migrations/create-migration (config request) data)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 201
       :headers {"Location" (migration-uri result)}
       :body (migration-api/->body result)})))

(defn- transition
  [request f]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [migration-id]} (:path parameters)
        result (f (config request)
                  bank-id
                  migration-id
                  (shared.actor/actor auth))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body (migration-api/->body result)})))

(defn approve-migration
  "Approve a migration. Moves nothing — it makes the migration work the
  scheduler picks up on its due date, which is the only thing that does."
  [request]
  (transition request migrations/approve-migration))

(defn cancel-migration
  "Cancel a migration, taking it off the scheduler's work list. A
  completed migration cannot be cancelled — its accounts have moved."
  [request]
  (transition request migrations/cancel-migration))

(defn preview-migration
  "Run a preview. This is the only evaluation the API can perform —
  running a migration is the scheduler's, so that no request can move
  a bank's accounts."
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [migration-id]} (:path parameters)
        result (migrations/preview-migration (config request)
                                             bank-id
                                             migration-id
                                             (utility/today))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 201
       :headers {"Location" (str (migration-uri {:migration-id migration-id})
                                 "/previews/"
                                 (:run-id result))}
       :body (migration-api/->run-body result)})))
