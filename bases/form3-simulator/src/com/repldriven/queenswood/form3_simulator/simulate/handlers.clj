(ns com.repldriven.queenswood.form3-simulator.simulate.handlers
  "The control routes every payment simulator shares, so a scenario runs
  on any of them. An inbound here is admitted before it settles, so the
  route answers once the bank has decided, with the admission's status
  and reason."
  (:require
    [com.repldriven.queenswood.form3-simulator.responses :as responses]
    [com.repldriven.queenswood.form3-simulator.scheme :as scheme]
    [com.repldriven.queenswood.form3-simulator.records :as records])
  (:import
    (java.math RoundingMode)))

(defn- amount
  [x]
  (.toPlainString (.setScale (bigdec x) 2 RoundingMode/HALF_EVEN)))

(defn- account-for
  [state sort-code bban]
  (when (and bban (= 14 (count bban)) (= sort-code (subs bban 0 6)))
    (records/account-by-number state (subs bban 0 6) (subs bban 6))))

(def ^:private not-held-here
  {:status 404
   :body {:title "NOT_FOUND"
          :type "simulate/unknown-account"
          :status 404
          :detail "No account registered here has this address"}})

(defn inbound-payment
  [request]
  (let [{:keys [state parameters sort-code]} request
        {:keys [bban currency reference debtor-name] :as body} (:body
                                                                parameters)
        account (account-for state sort-code bban)]
    (if (nil? account)
      not-held-here
      (let [{:keys [payment-id admission-status status-reason]}
            (scheme/admit state
                          (responses/config request)
                          {:account account
                           :amount (amount (:amount body))
                           :currency currency
                           :reference reference
                           :debtor-name (or debtor-name "Simulated Debtor")})]
        {:status 202
         :body {:endToEndIdentification payment-id
                :admission-status admission-status
                :status-reason status-reason}}))))

(defn outbound-return
  [request]
  (let [{:keys [state parameters]} request
        {:keys [end-to-end-id reason-code]} (:body parameters)
        res (scheme/return-outbound state
                                    (responses/config request)
                                    end-to-end-id
                                    (or reason-code "AC04"))]
    (cond
     (nil? res)
     {:status 404
      :body {:title "NOT_FOUND"
             :type "simulate/unknown-payment"
             :status 404
             :detail "No payment the simulator holds has this end-to-end id"}}

     (:refused res)
     {:status 409
      :body {:title "CONFLICT"
             :type "simulate/not-returnable"
             :status 409
             :detail (:refused res)}}

     :else
     {:status 202 :body res})))

(defn open-refused
  [request]
  (swap! (:state request) assoc :refuse-next true)
  {:status 204})
