(ns com.repldriven.queenswood.test-projections.accounts
  (:require
    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]))

(defn- reverse-by-real-id
  [m]
  (into {} (map (fn [[mid {:keys [real-id]}]] [real-id mid])) m))

(defn- normalise-status
  [status]
  (case status
    :cash-account-status-opening :open
    :cash-account-status-opened :open
    ;; The model has no suspended state and the balance is still owed,
    ;; so suspension reads as open rather than as a state of its own.
    :cash-account-status-suspended :open
    :cash-account-status-closing :closed
    :cash-account-status-closed :closed
    ;; A status nobody mapped surfaces as an equality failure naming it,
    ;; not as an exception from `case`.
    status))

(defn project-accounts
  [bank ctx]
  (let [{:keys [id-mapping accounts banks products parties]} ctx
        bank-real->model (reverse-by-real-id banks)
        prod-real->model (reverse-by-real-id products)
        party-real->model (reverse-by-real-id parties)]
    (->> (:real->model id-mapping)
         (map (fn [[real-acct-id model-acct-id]]
                (let [model-bank (get-in accounts [model-acct-id :bank])
                      bank-real-id (get-in banks [model-bank :real-id])
                      account (cash-accounts/get-account bank
                                                         bank-real-id
                                                         real-acct-id)]
                  [model-acct-id
                   {:bank (bank-real->model (:bank-id account))
                    :product (prod-real->model (:product-id account))
                    :party (party-real->model (:party-id account))
                    :status (normalise-status (:account-status account))}])))
         (into {}))))

(defn project-model-accounts
  [model-state]
  (->> (:accounts model-state)
       (map (fn [[acct-id {:keys [bank product party status]}]]
              [acct-id
               {:bank bank
                :product product
                :party party
                :status status}]))
       (into {})))
