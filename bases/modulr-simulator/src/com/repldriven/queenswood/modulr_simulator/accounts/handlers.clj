(ns com.repldriven.queenswood.modulr-simulator.accounts.handlers
  (:require
    [com.repldriven.queenswood.modulr-simulator.ledger :as ledger]
    [com.repldriven.queenswood.modulr-simulator.signed :as signed]))

(def create
  (signed/verified
   (fn [request]
     (let [{:keys [state sort-code parameters]} request
           {:keys [path body]} parameters
           account
           (ledger/open-account state (:customerId path) sort-code body)]
       (if-let [refused (:refused account)]
         (signed/refusal "BUSINESSRULE" refused)
         {:status 201 :body (ledger/account-response account)})))))

(def fetch
  (signed/verified (fn [request]
                     (let [{:keys [state parameters]} request]
                       {:status 200
                        :body (ledger/account-response
                               (ledger/account
                                state
                                (get-in parameters [:path :accountId])))}))))

(def block
  (signed/verified (fn [request]
                     (let [{:keys [state parameters]} request]
                       (ledger/set-status state
                                          (get-in parameters [:path :accountId])
                                          "BLOCKED")
                       {:status 204}))))

(def close
  (signed/verified (fn [request]
                     (let [{:keys [state parameters]} request
                           res (ledger/close-account
                                state
                                (get-in parameters [:path :accountId]))]
                       (if-let [refused (:refused res)]
                         (signed/refusal "BUSINESSRULE" refused)
                         {:status 204})))))
