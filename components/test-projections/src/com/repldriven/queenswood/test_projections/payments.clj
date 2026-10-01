(ns com.repldriven.queenswood.test-projections.payments
  (:require
    [com.repldriven.queenswood.payment-query.interface :as payment]))

(defn- bare-status
  [v]
  (when v
    (keyword (subs (name v) (count "outbound-payment-status-")))))

(defn project-outbound-payments
  [bank model->real]
  (->> model->real
       (map (fn [[model-id {:keys [real-id]}]]
              [model-id
               (bare-status (:payment-status
                             (payment/get-outbound-payment bank real-id)))]))
       (into {})))

(defn project-model-outbound-payments
  [model-state]
  (update-vals (:payments model-state) :status))

(defn project-inbound-payments
  [bank stx-ids]
  (->> stx-ids
       (filter (fn [[_marker stx-id]]
                 (some? (payment/get-inbound-payment bank stx-id))))
       (map key)
       set))

(defn project-model-inbound-payments
  [model-state]
  (:inbound-payments model-state))
