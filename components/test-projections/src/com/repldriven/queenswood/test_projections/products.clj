(ns com.repldriven.queenswood.test-projections.products
  (:require
    [com.repldriven.queenswood.cash-account-product-query.interface :as
     products]))

(defn- normalise-status
  [status]
  (case status
    :version-status-draft :draft
    :version-status-published :published))

(defn- versions-from-aggregate
  [aggregate]
  (mapv (fn [v]
          ;; A version drops `:effective-to` when it has none — the
          ;; model carries it as a scalar, nil included.
          {:status (normalise-status (:status v))
           :number (:version-number v)
           :currency (:currency v)
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
                     (keep (fn [[model-id {:keys [real-id]}]]
                             (when-let [aggregate (get items-by-id real-id)]
                               [model-id
                                (versions-from-aggregate aggregate)]))
                           entries))))
         (into {}))))

(defn project-model-products
  [model-state]
  (update-vals (:products model-state)
               (fn [prod]
                 (mapv (fn [v]
                         (select-keys v
                                      [:status :number :currency :effective-from
                                       :effective-to]))
                       (reverse (:versions prod))))))
