(ns com.repldriven.queenswood.payee-check.core
  (:require
    [com.repldriven.queenswood.payee-check.domain :as domain]
    [com.repldriven.queenswood.payee-check.store :as store]

    [com.repldriven.queenswood.audit.interface :as audit]
    [com.repldriven.queenswood.bank-query.interface :as bank-query]
    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]
    [com.repldriven.queenswood.payment-provider.interface :as
     payment-provider]
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.utility.interface :as utility]))

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
      domain/unavailable
      (let [{:keys [match-result actual-name reason-code reason]}
            (http/res->edn res)]
        {:match-result (keyword match-result)
         :actual-name actual-name
         :reason-code reason-code
         :reason reason}))))

(defn- get-aggregates
  [txn bank-id checked-on]
  (let-nom> [{:keys [count]} (store/get-aggregates txn bank-id checked-on)]
    {:payee-check {#{:bank-id :checked-on} count}}))

(defn- save-unanswered
  [config bank-id request actor]
  (store/transact
   config
   (fn [txn]
     (let [checked-on (utility/today)]
       (let-nom> [policies (policy/get-effective-policies txn
                                                          {:bank-id bank-id})
                  aggregates (get-aggregates txn bank-id checked-on)
                  check (domain/new-check bank-id
                                          request
                                          actor
                                          checked-on
                                          aggregates
                                          policies)
                  _ (store/save-check txn check)]
         check)))
   :payee-check/save
   "Failed to save payee check"))

(defn- save-answered
  [config check result actor request-id]
  (let [answered (domain/answered check result)]
    (store/transact
     config
     (fn [txn]
       (let-nom> [_ (store/save-check txn answered)
                  _ (audit/record txn
                                  {:bank-id (:bank-id answered)
                                   :kind :audit-kind-payee-check-made
                                   :subject-kind :audit-subject-kind-payee-check
                                   :subject-id (:check-id answered)
                                   :request-id request-id
                                   :after answered
                                   :performed-by actor})]
         answered))
     :payee-check/save
     "Failed to save payee check")))

(defn check-and-save
  [config data]
  (let [{:keys [payment-providers adapter-urls]} config
        {:keys [bank-id account-id actor request-id]} data
        request (dissoc data :bank-id :account-id :actor :request-id)]
    (let-nom> [_ (when account-id
                   (cash-accounts/get-account config bank-id account-id))
               bank (bank-query/find-bank config bank-id)
               {:keys [provider]} (when payment-providers
                                    (payment-provider/for-bank
                                     payment-providers
                                     bank))
               check (save-unanswered config bank-id request actor)]
      (save-answered config
                     check
                     (perform-cop-check
                      (get adapter-urls (keyword provider))
                      bank-id
                      (assoc request :account-id account-id))
                     actor
                     request-id))))

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

(defn expires-at
  [check]
  (domain/expires-at check))
