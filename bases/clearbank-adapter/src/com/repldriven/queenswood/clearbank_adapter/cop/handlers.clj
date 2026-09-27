(ns com.repldriven.queenswood.clearbank-adapter.cop.handlers
  (:require
    [com.repldriven.queenswood.clearbank-webhook.interface :as
     clearbank-webhook]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]))

(defn- ->clearbank-request
  [clearbank-url signing-key request]
  (let [{:keys [creditor-name account account-type]} request
        {:keys [sort-code account-number]} account
        body (json/write-str {:accountDetails {:sortCode sort-code
                                               :accountNumber account-number}
                              :accountHolderName creditor-name
                              :accountType (case account-type
                                             :account-type-personal "Personal"
                                             :account-type-business "Business"
                                             "Personal")
                              :endToEndIdentification "cop-api"})]
    (let-nom> [signature (clearbank-webhook/sign (:private-key signing-key)
                                                 body)]
      {:method :post
       :url (str clearbank-url "/v1/confirmation-of-payee/outbound")
       :headers {"Content-Type" "application/json"
                 clearbank-webhook/signature-header signature}
       :body body})))

(defn- ->match-result
  [s]
  (case s
    "Match" :match-result-match
    "CloseMatch" :match-result-close-match
    "NoMatch" :match-result-no-match
    "Unavailable" :match-result-unavailable
    :match-result-unavailable))

(defn- ->result
  [response-body]
  (let [{:keys [matchResult actualName reasonCode reason]} response-body]
    {:match-result (->match-result matchResult)
     :actual-name actualName
     :reason-code reasonCode
     :reason reason}))

(def ^:private unavailable-result
  {:match-result :match-result-unavailable
   :reason-code "ACNS"
   :reason "CoP service unavailable"})

(defn outbound-cop
  [_config]
  (fn [request]
    (let [{:keys [clearbank-url signing-key parameters]} request
          {:keys [body]} parameters
          {:keys [creditor-name]} body
          _ (log/info "Outbound CoP check" {:creditor-name creditor-name})
          res (error/try-nom
               :payee-check/unavailable
               "Confirmation of Payee request failed"
               (let-nom> [req (->clearbank-request clearbank-url
                                                   signing-key
                                                   body)]
                 (http/request req)))]
      (if (and (map? res) (= 200 (:status res)))
        {:status 200 :body (->result (http/res->edn res))}
        {:status 200 :body unavailable-result}))))
