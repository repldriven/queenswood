(ns com.repldriven.queenswood.modulr-simulator.notifications.handlers
  (:require
    [com.repldriven.queenswood.modulr-simulator.ledger :as ledger]
    [com.repldriven.queenswood.modulr-simulator.signed :as signed]))

(def register
  (signed/verified
   (fn [request]
     (let [{:keys [state parameters]} request
           {:keys [path body]} parameters]
       {:status 201
        :body (ledger/register-notification state (:customerId path) body)}))))

(def list-registered
  (signed/verified
   (fn [request]
     (let [{:keys [state parameters]} request]
       {:status 200
        :body {:content (vec (ledger/notifications
                              state
                              (get-in parameters [:path :customerId])))}}))))
