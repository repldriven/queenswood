(ns com.repldriven.queenswood.modulr-simulator.simulate.handlers
  "The control routes every payment simulator serves, so a scenario runs
  on any of them, and what a rig that posts the ledger itself needs to
  keep the balances here beside it."
  (:require
    [com.repldriven.queenswood.modulr-simulator.deliveries :as deliveries]
    [com.repldriven.queenswood.modulr-simulator.ledger :as ledger]
    [com.repldriven.queenswood.modulr-simulator.scheme :as scheme]

    [com.repldriven.mono.log.interface :as log]

    [clojure.string :as str]))

(defn- account-for
  [state bban]
  (when (and bban (>= (count bban) 14))
    (ledger/account-by-scan state (subs bban 0 6) (subs bban 6))))

(defn- config
  [request]
  (select-keys request
               [:webhook-delay-ms :pending-for-funds-ms
                :payment-scheme]))

(defn- pause
  [{:keys [webhook-delay-ms]}]
  (when (pos? (or webhook-delay-ms 0)) (Thread/sleep (long webhook-delay-ms))))

(def ^:private not-held-here
  {:status 404
   :body {:title "NOT_FOUND"
          :type "simulate/unknown-account"
          :status 404
          :detail "No account the simulator holds has this address"}})

(defn- arrive
  [state config account p {:keys [debtor-name amount currency reference]}]
  (ledger/credit state (:id account) amount)
  (deliveries/notify state
                     (:customer-id account)
                     "PAYIN"
                     (scheme/payin {:payment-id (:id p)
                                    :transaction-id (str "T" (:id p))
                                    :type "PI_FAST"
                                    :account account
                                    :payer {:Name (or debtor-name
                                                      "Simulated Debtor")}
                                    :amount amount
                                    :currency currency
                                    :reference reference}))
  (scheme/release-pending state config (:id account)))

(defn inbound-payment
  [request]
  (let [{:keys [state parameters]} request
        {:keys [bban amount currency reference debtor-name outcome] :as body}
        (:body parameters)
        account (account-for state bban)
        config (config request)]
    (if (or (nil? account) (= "CLOSED" (:status account)))
      not-held-here
      (let [p (ledger/new-payment state
                                  {:status "PROCESSED"
                                   :type "PAYIN"
                                   :details {:accountId (:id account)
                                             :payee {:sortCode (:sort-code
                                                                account)
                                                     :accountNumber
                                                     (:account-number account)}
                                             :payerName debtor-name
                                             :amount amount
                                             :currency currency
                                             :reference reference}})
            notify (fn [status]
                     (deliveries/notify state
                                        (:customer-id account)
                                        "PAYMENT_COMPLIANCE_STATUS"
                                        (scheme/compliance account
                                                           (:id p)
                                                           status)))]
        (future
         (try
           (pause config)
           (if (= scheme/held-name debtor-name)
             (do (notify "HELD")
                 (pause config)
                 (if (= "return" outcome)
                   (notify "RETURNED")
                   (do (notify "RELEASED")
                       (arrive state config account p body))))
             (arrive state config account p body))
           (catch Exception e
             (log/error e "Modulr simulator inbound payment threw"))))
        {:status 202 :body {:endToEndIdentification (:id p)}}))))

(def ^:private return-reasons
  "Modulr's return reason for each ISO 20022 code a scenario may ask for."
  {"AC04" "BENACCCLOSED" "AC01" "BENSCANUNKNOWN" "AM05" "DUPLICATE"})

(defn- reference
  "An end-to-end id as the adapter wrote it into a payment's external
  reference, which allows no `.`."
  [end-to-end-id]
  (str/replace end-to-end-id "." "-"))

(defn outbound-return
  [request]
  (let [{:keys [state parameters]} request
        {:keys [end-to-end-id reason-code]} (:body parameters)
        p (first (ledger/find-payments state
                                       {:externalReference (reference
                                                            end-to-end-id)}))
        returned (when p
                   (scheme/return-payment state
                                          (config request)
                                          (:id p)
                                          (get return-reasons
                                               reason-code
                                               (or reason-code
                                                   "BENACCCLOSED"))))]
    (cond
     (nil? p)
     {:status 404
      :body {:title "NOT_FOUND"
             :type "simulate/unknown-payment"
             :status 404
             :detail "No payment the simulator holds has this end-to-end id"}}

     (:refused returned)
     {:status 409
      :body {:title "CONFLICT"
             :type "simulate/not-returnable"
             :status 409
             :detail (:refused returned)}}

     :else
     {:status 202 :body {:payment-id (:id p)}})))

(defn open-refused
  [request]
  (swap! (:state request) assoc :refuse-next true)
  {:status 204})

(defn close-refused
  [request]
  (swap! (:state request) assoc :refuse-next-close true)
  {:status 204})

(defn fund
  [request]
  (let [{:keys [state parameters]} request
        {:keys [bban amount]} (:body parameters)
        account (account-for state bban)]
    (if (nil? account)
      not-held-here
      (do (ledger/credit state (:id account) amount)
          (scheme/release-pending state (config request) (:id account))
          {:status 204}))))

(defn balances
  [request]
  {:status 200
   :body {:accounts (mapv ledger/account-response
                          (remove :unbounded
                                  (vals (:accounts @(:state request)))))}})
