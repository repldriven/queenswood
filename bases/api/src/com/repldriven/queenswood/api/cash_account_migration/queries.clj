(ns com.repldriven.queenswood.api.cash-account-migration.queries
  (:require
    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.cash-account-migration.interface :as migrations]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- config
  [{:keys [record-db record-store]}]
  {:record-db record-db :record-store record-store})

(defn list-migrations
  [request]
  (let [{:keys [bank-id]} (:auth request)
        {:keys [page]} (:query (:parameters request))
        result (let-nom>
                 [{:keys [migrations] :as found}
                  (migrations/list-migrations (config request)
                                              bank-id
                                              (cursor/page-opts page))]
                 (cursor/page-body "/v1/cash-account-migrations"
                                   page
                                   migrations
                                   found))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))

(defn get-migration
  [request]
  (let [{:keys [bank-id]} (:auth request)
        {:keys [migration-id]} (:path (:parameters request))
        result (migrations/get-migration (config request) bank-id migration-id)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))

(defn list-runs
  [request]
  (let [{:keys [bank-id]} (:auth request)
        {:keys [path query]} (:parameters request)
        {:keys [migration-id]} path
        {:keys [page]} query
        result (let-nom>
                 ;; Reading the migration first turns an unknown id into
                 ;; a 404 rather than an empty list, which would read as
                 ;; "no previews yet".
                 [_ (migrations/get-migration (config request)
                                              bank-id
                                              migration-id)
                  runs (migrations/list-runs (config request)
                                             bank-id
                                             migration-id)
                  windowed (cursor/window runs :run-id :desc page)]
                 (cursor/page-body (str "/v1/cash-account-migrations/"
                                        migration-id
                                        "/previews")
                                   page
                                   (:page windowed)
                                   windowed))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))

(defn get-run
  [request]
  (let [{:keys [bank-id]} (:auth request)
        {:keys [migration-id run-id]} (:path (:parameters request))
        result (let-nom>
                 [run (migrations/get-run (config request) bank-id run-id)
                  _ (when-not (= migration-id (:migration-id run))
                      (error/reject :cash-account-migration/run-not-found
                                    {:message "Migration run not found"
                                     :migration-id migration-id
                                     :run-id run-id}))]
                 run)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))

(defn list-run-accounts
  [request]
  (let [{:keys [bank-id]} (:auth request)
        {:keys [path query]} (:parameters request)
        {:keys [migration-id run-id]} path
        {:keys [page]} query
        result (let-nom>
                 [run (migrations/get-run (config request) bank-id run-id)
                  _ (when-not (= migration-id (:migration-id run))
                      (error/reject :cash-account-migration/run-not-found
                                    {:message "Migration run not found"
                                     :migration-id migration-id
                                     :run-id run-id}))
                  {:keys [account-runs] :as found}
                  (migrations/list-run-accounts (config request)
                                                bank-id
                                                run-id
                                                (cursor/page-opts page))]
                 (cursor/page-body (str "/v1/cash-account-migrations/"
                                        migration-id
                                        "/previews/"
                                        run-id
                                        "/accounts")
                                   page
                                   account-runs
                                   found))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))
