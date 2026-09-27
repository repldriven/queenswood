(ns com.repldriven.queenswood.form3-simulator.subscriptions.handlers
  (:require
    [com.repldriven.queenswood.form3-simulator.responses :as responses]
    [com.repldriven.queenswood.form3-simulator.signed :as signed]
    [com.repldriven.queenswood.form3-simulator.records :as records]))

(def register
  (signed/verified
   (fn [request]
     (let [{:keys [state parameters organisation-id]} request
           {:keys [id attributes]} (get-in parameters [:body :data])]
       (responses/ok
        201
        (records/put
         state
         :subscriptions
         (records/resource organisation-id "subscriptions" id attributes)))))))

(def list-registered
  (signed/verified (fn [request]
                     {:status 200
                      :body {:data (mapv records/public
                                         (vals (:subscriptions
                                                @(:state request))))}})))

(def delete
  (signed/verified (fn [request]
                     (let [{:keys [state parameters]} request]
                       (swap! state update
                         :subscriptions
                         dissoc
                         (get-in parameters [:path :id]))
                       {:status 204}))))
