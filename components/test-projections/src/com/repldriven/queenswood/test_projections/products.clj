(ns com.repldriven.queenswood.test-projections.products
  (:require
    [com.repldriven.queenswood.cash-account-product-query.interface :as
     products]))

(defn- normalise-status
  [status]
  (case status
    :cash-account-product-status-draft :draft
    :cash-account-product-status-published :published
    :cash-account-product-status-discarded :discarded))

(defn- versions-from-aggregate
  [aggregate]
  (mapv (fn [v]
          ;; A version stores one currency in `:allowed-currencies`,
          ;; and drops `:effective-to` when it has none — the model
          ;; carries both as scalars, nil included.
          {:status (normalise-status (:status v))
           :number (:version-number v)
           :currency (first (:allowed-currencies v))
           :effective-from (:effective-from v)
           :effective-to (:effective-to v)})
        (:versions aggregate)))

(defn project-products
  [bank model->real]
  (let [by-bank (group-by (fn [[_ entry]] (:bank-real-id entry))
                          model->real)]
    (->> by-bank
         (mapcat (fn [[bank-real-id entries]]
                   (let [{:keys [items]} (products/get-products bank
                                                                bank-real-id)
                         items-by-id (into {}
                                           (map (juxt :product-id identity))
                                           items)]
                     (map (fn [[model-id {:keys [real-id]}]]
                            [model-id
                             (versions-from-aggregate
                              (get items-by-id real-id))])
                          entries))))
         (into {}))))

(defn project-model-products
  [model-state]
  (update-vals (:products model-state)
               (fn [prod] (vec (reverse (:versions prod))))))
