(ns com.repldriven.queenswood.clearbank-simulator.accounts.handlers
  (:require
    [com.repldriven.queenswood.clearbank-simulator.signed :as signed]

    [com.repldriven.mono.utility.interface :refer [uuidv7]]))

(defn- issued
  [id sort-code account-number]
  {:id id :sortCode sort-code :accountNumber account-number})

(defn- take-refusal
  "True, once, after a control route asked for the next call `flag`
  names to be refused."
  [accounts flag]
  (locking accounts
    (let [refuse (get @accounts flag)]
      (when refuse (swap! accounts dissoc flag))
      refuse)))

(defn- refused
  [detail]
  {:status 422
   :body {:type ":payment-account/declined"
          :title "REJECTED"
          :status 422
          :detail detail}})

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
       (if (take-refusal accounts :refuse-next-close)
         (refused "The account could not be closed")
         (do (swap! accounts update :virtual-accounts dissoc id)
             {:status 200 :body {:id id}}))))))

(defn reissue
  [_config]
  (signed/verified
   (fn [request]
     (let [{:keys [accounts parameters]} request
           {:keys [id]} (:path parameters)
           {:keys [body]} parameters
           {:keys [sortCode accountNumber]} body]
       (if (take-refusal accounts :refuse-next-reissue)
         (refused "The account number could not be reissued")
         (do (swap! accounts assoc-in [:virtual-accounts id] body)
             {:status 200 :body (issued id sortCode accountNumber)}))))))

(defn refuse-next
  "A control route setting `flag`, so the next call it names is
  refused."
  ([config] (refuse-next config :refuse-next))
  ([_config flag]
   (fn [request]
     (swap! (:accounts request) assoc flag true)
     {:status 204})))
