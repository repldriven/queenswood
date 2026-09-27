(ns com.repldriven.queenswood.clearbank-simulator.accounts.handlers
  (:require
    [com.repldriven.queenswood.clearbank-simulator.signed :as signed]

    [com.repldriven.mono.utility.interface :refer [uuidv7]]))

(defn- issued
  [id sort-code account-number]
  {:id id :sortCode sort-code :accountNumber account-number})

(defn create
  [_config]
  (signed/verified
   (fn [request]
     (let [{:keys [accounts parameters]} request
           {:keys [body]} parameters
           {:keys [sortCode accountNumber]} body]
       (if (:refuse-next @accounts)
         (do (swap! accounts dissoc :refuse-next)
             {:status 422
              :body {:type ":payment-account/declined"
                     :title "REJECTED"
                     :status 422
                     :detail "The account was declined"}})
         (let [id (str "va-" (uuidv7))]
           (swap! accounts assoc-in [:virtual-accounts id] body)
           {:status 201 :body (issued id sortCode accountNumber)}))))))

(defn close
  [_config]
  (signed/verified
   (fn [request]
     (let [{:keys [accounts parameters]} request
           {:keys [id]} (:path parameters)]
       (swap! accounts update :virtual-accounts dissoc id)
       {:status 200 :body {:id id}}))))

(defn reissue
  [_config]
  (signed/verified
   (fn [request]
     (let [{:keys [accounts parameters]} request
           {:keys [id]} (:path parameters)
           {:keys [body]} parameters
           {:keys [sortCode accountNumber]} body]
       (swap! accounts assoc-in [:virtual-accounts id] body)
       {:status 200 :body (issued id sortCode accountNumber)}))))

(defn refuse-next
  [_config]
  (fn [request]
    (swap! (:accounts request) assoc :refuse-next true)
    {:status 204}))
