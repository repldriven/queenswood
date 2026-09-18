(ns com.repldriven.queenswood.api.cash-account-product.queries
  (:require
    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.cash-account-product-query.interface :as
     cash-account-products]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

(defn list-products
  [request]
  (let [{:keys [record-db record-store auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [query]} parameters
        {:keys [page]} query
        {:keys [after before size]} page
        after-id (cursor/decode after)
        before-id (cursor/decode before)
        result (cash-account-products/get-products
                {:record-db record-db :record-store record-store}
                bank-id)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      (let [{:keys [items]} result
            windowed (cursor/paginate (or items [])
                                      :product-id
                                      :desc
                                      {:after after-id
                                       :before before-id
                                       :size size})
            {windowed-items :page
             next-cursor :after
             prev-cursor :before}
            windowed
            links (when (seq windowed-items)
                    (cursor/build-links "/v1/cash-account-products"
                                        (cursor/clamp-size size)
                                        (when after-id prev-cursor)
                                        next-cursor))]
        {:status 200
         :body (utility/assoc-seq
                {:items windowed-items}
                :links
                links)}))))

(defn get-product
  [request]
  (let [{:keys [record-db record-store auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [product-id]} path
        result (cash-account-products/get-product
                {:record-db record-db :record-store record-store}
                bank-id
                product-id)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))

(defn get-version
  [request]
  (let [{:keys [record-db record-store auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [product-id version-id]} path
        result (cash-account-products/get-version
                {:record-db record-db :record-store record-store}
                bank-id
                product-id
                version-id)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))

(defn list-templates
  [request]
  (let [{:keys [record-db record-store]} request
        result (cash-account-products/list-templates
                {:record-db record-db :record-store record-store})]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body {:items result}})))
