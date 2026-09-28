(ns com.repldriven.queenswood.payee-check.core
  (:require
    [com.repldriven.queenswood.payee-check.domain :as domain]
    [com.repldriven.queenswood.payee-check.store :as store]

    [com.repldriven.queenswood.bank-query.interface :as bank-query]
    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]
    [com.repldriven.queenswood.payment-provider.interface :as
     payment-provider]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]))

(def ^:private unavailable
  {:match-result :match-result-unavailable
   :reason-code "ACNS"
   :reason "CoP service unavailable"})

(defn- perform-cop-check
  "Invoke the CoP adapter for a single check request. No adapter, a
  transport failure or a non-200 response degrades to an `unavailable`
  result rather than an anomaly, so the check is still persisted."
  [adapter-url bank-id request]
  (let [{:keys [creditor-name account account-type account-id]} request
        {:keys [sort-code account-number]} account
        res (when adapter-url
              (error/try-nom
               :payee-check/cop
               "CoP request to adapter failed"
               (http/request
                {:method :post
                 :url (str adapter-url "/cop/outbound")
                 :headers {"Content-Type" "application/json"}
                 :body (json/write-str
                        (cond-> {:creditor-name creditor-name
                                 :account {:sort-code sort-code
                                           :account-number account-number}
                                 :account-type account-type
                                 :bank-id bank-id}
                                account-id
                                (assoc :account-id account-id)))})))]
    (if (or (nil? res) (error/anomaly? res) (not= 200 (:status res)))
      unavailable
      (let [{:keys [match-result actual-name reason-code reason]}
            (http/res->edn res)]
        {:match-result (keyword match-result)
         :actual-name actual-name
         :reason-code reason-code
         :reason reason}))))

(defn check-payee
  [config bank-id request result]
  (let [check (domain/new-check bank-id request result)]
    (let-nom> [_ (store/save-check config check)]
      check)))

(defn check-and-save
  [config data]
  (let [{:keys [payment-providers adapter-urls]} config
        {:keys [bank-id account-id]} data
        request (dissoc data :bank-id :account-id)]
    (let-nom> [_ (when account-id
                   (cash-accounts/get-account config bank-id account-id))
               bank (bank-query/find-bank config bank-id)
               {:keys [provider]} (when payment-providers
                                    (payment-provider/for-bank
                                     payment-providers
                                     bank))]
      (check-payee config
                   bank-id
                   request
                   (perform-cop-check
                    (get adapter-urls (keyword provider))
                    bank-id
                    (assoc request :account-id account-id))))))

(defn get-check
  [txn bank-id check-id]
  (let-nom> [check (store/get-check txn bank-id check-id)]
    (or check
        (error/reject :payee-check/not-found
                      {:message "Payee check not found"
                       :check-id check-id}))))

(defn get-checks
  ([txn bank-id]
   (store/get-checks txn bank-id))
  ([txn bank-id opts]
   (store/get-checks txn bank-id opts)))
