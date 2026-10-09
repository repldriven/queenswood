(ns com.repldriven.queenswood.test-projections.balances
  (:require
    [com.repldriven.queenswood.balance-query.interface :as balance]))

(defn- available
  [bank bank-real-id account-id]
  (or (-> (balance/get-balances bank bank-real-id account-id "GBP")
          :available-balance
          :value)
      0))

(defn real->bank
  "Real account id to its bank's real id, built from the runner's own
  ctx maps. A balance is keyed by bank, so projecting one means knowing
  which bank each account belongs to — the id mapping alone does not
  say."
  [accounts banks model->real]
  (into {}
        (keep (fn [[model-acct {:keys [bank]}]]
                (when-let [real-id (get model->real model-acct)]
                  [real-id (get-in banks [bank :real-id])])))
        accounts))

(defn project-balances
  [bank real->bank-id id-mapping]
  (->> id-mapping
       (map (fn [[real-id model-id]]
              [model-id
               (available bank (get real->bank-id real-id) real-id)]))
       (into {})))

(defn project-model-balances
  [model-state]
  (-> (:accounts model-state)
      (update-vals :available)))
