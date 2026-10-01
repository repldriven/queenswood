(ns com.repldriven.queenswood.test-scenarios.projection
  (:require
    [com.repldriven.queenswood.test-projections.interface :as projections]))

(defn- with-bank-real-id
  "Each tracked product or party's model id to its real id and its bank's
  real id, which the real-side projections read by."
  [model->real banks]
  (update-vals model->real
               (fn [{:keys [real-id bank]}]
                 {:real-id real-id
                  :bank-real-id (get-in banks [bank :real-id])})))

(defn real
  [bank ctx]
  (let [{:keys [id-mapping accounts banks products parties payments]} ctx
        {:keys [model->real real->model]} id-mapping
        real->bank (projections/real->bank accounts banks model->real)]
    {:balances (projections/project-balances bank real->bank real->model)
     :interest (projections/project-interest bank real->bank real->model)
     :products (projections/project-products bank
                                             (with-bank-real-id products banks))
     :parties (projections/project-parties bank
                                           (with-bank-real-id parties banks))
     :banks (projections/project-banks bank ctx)
     :accounts (projections/project-accounts bank ctx)
     :transactions (projections/project-transactions bank real->model)
     :outbound-payments (projections/project-outbound-payments bank payments)
     :inbound-payments (projections/project-inbound-payments
                        bank
                        (:inbound-stx ctx))}))

(defn model
  [state]
  {:balances (projections/project-model-balances state)
   :interest (projections/project-model-interest state)
   :products (projections/project-model-products state)
   :parties (projections/project-model-parties state)
   :banks (projections/project-model-banks state)
   :accounts (projections/project-model-accounts state)
   :transactions (projections/project-model-transactions state)
   :outbound-payments (projections/project-model-outbound-payments state)
   :inbound-payments (projections/project-model-inbound-payments state)})
