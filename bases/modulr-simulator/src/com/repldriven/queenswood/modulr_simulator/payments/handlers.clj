(ns com.repldriven.queenswood.modulr-simulator.payments.handlers
  (:require
    [com.repldriven.queenswood.modulr-simulator.deliveries :as deliveries]
    [com.repldriven.queenswood.modulr-simulator.ledger :as ledger]
    [com.repldriven.queenswood.modulr-simulator.scheme :as scheme]
    [com.repldriven.queenswood.modulr-simulator.signed :as signed]))

(def refused-sort-code
  "A destination under this sort code is refused when submitted, before
  any money moves."
  "999998")

(defn- config
  [request]
  (select-keys request [:webhook-delay-ms :pending-for-funds-ms]))

(def create
  (signed/verified
   (fn [request]
     (let [{:keys [state parameters]} request
           {:keys [body]} parameters
           {:keys [sourceAccountId destination]} body
           source (ledger/account state sourceAccountId)]
       (cond
        (not= "ACTIVE" (:status source))
        (signed/refusal "BUSINESSRULE" "The source account is not active")

        (= refused-sort-code (:sortCode destination))
        {:status 400
         :body [{:field "destination.sortCode"
                 :code "INVALID"
                 :message "The destination sort code is not reachable"}]}

        :else
        {:status 201
         :body (ledger/payment-response
                (scheme/submit state (config request) body))})))))

(def search
  (signed/verified
   (fn [request]
     (let [{:keys [state parameters]} request
           found (mapv ledger/payment-response
                       (ledger/find-payments state (:query parameters)))]
       {:status 200
        :body {:content found
               :page 0
               :size (count found)
               :totalPages 1
               :totalSize (count found)}}))))

(def credit
  (signed/verified
   (fn [request]
     (let [{:keys [state parameters]} request
           {:keys [accountId amount description type payerDetail]} (:body
                                                                    parameters)
           account (ledger/account state accountId)
           p (ledger/new-payment state
                                 {:status "PROCESSED"
                                  :type "PAYIN"
                                  :details {:accountId accountId
                                            :amount amount
                                            :reference description}})]
       (ledger/credit state accountId amount)
       (future (deliveries/notify state
                                  (:customer-id account)
                                  "PAYIN"
                                  (scheme/payin
                                   {:payment-id (:id p)
                                    :transaction-id (str "T" (:id p))
                                    :type (or type "PI_FAST")
                                    :account account
                                    :payer {:Name (:name payerDetail)}
                                    :amount amount
                                    :reference description}))
               (scheme/release-pending state (config request) accountId))
       {:status 200}))))
