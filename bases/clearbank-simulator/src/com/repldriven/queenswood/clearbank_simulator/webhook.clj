(ns com.repldriven.queenswood.clearbank-simulator.webhook
  (:require
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :refer
     [assoc-some now-rfc3339 uuidv7]]))

(defn- nonce
  []
  (rand-int Integer/MAX_VALUE))

(defn fire
  [config sort-code type payload]
  (let [url (get-in @(:webhooks config) [sort-code type])]
    (if-not url
      (do (log/warn "No webhook registered for"
                    sort-code
                    type)
          nil)
      (let [body (json/write-str
                  {:Type type
                   :Version (case type
                              "TransactionSettled" 6
                              "TransactionRejected" 2
                              "OutboundHeldTransaction" 1
                              "InboundHeldTransaction" 1
                              "PaymentMessageAssessmentFailed" 1
                              1)
                   :Payload payload
                   :Nonce (nonce)})
            res (http/request {:method :post
                               :url url
                               :headers {"Content-Type"
                                         "application/json"}
                               :body body})]
        (when (or (error/anomaly? res)
                  (and (:status res) (>= (:status res) 400)))
          (log/error "Webhook delivery failed for" type
                     "to" url
                     ":" res))
        res))))

(defn fire-transaction-settled
  [config sort-code e2e-id debit-credit-code body]
  (let [{:keys [bban amount currency reference
                creditor-bban debtor-name]}
        body]
    (fire config
          sort-code
          "TransactionSettled"
          {:TransactionId (str (uuidv7))
           :Status "Settled"
           :Scheme "FasterPayments"
           :EndToEndTransactionId e2e-id
           :Amount amount
           :CurrencyCode (or currency "GBP")
           :DebitCreditCode (if (= :credit debit-credit-code)
                              "Credit"
                              "Debit")
           :TimestampSettled (now-rfc3339)
           :TimestampCreated (now-rfc3339)
           :Reference (or reference "")
           :IsReturn false
           :Account {:BBAN (or creditor-bban bban)}
           :CounterpartAccount
           {:OwnerName (or debtor-name "Simulated Debtor")}})))

(defn fire-outbound-held-transaction
  [config sort-code e2e-id body]
  (let [{:keys [amount currency reference debtor-bban creditor-bban]} body]
    (fire config
          sort-code
          "OutboundHeldTransaction"
          {:TimestampCreated (now-rfc3339)
           :Scheme "FasterPayments"
           :Account {:BBAN debtor-bban}
           :CounterpartAccount {:BBAN creditor-bban}
           :TransactionAmount amount
           :CurrencyCode (or currency "GBP")
           :PaymentReference (or reference "")
           :EndToEndTransactionId e2e-id})))

(defn fire-inbound-held-transaction
  "Fires an InboundHeldTransaction webhook — ClearBank is holding an inbound
  for screening. `Account` is the recipient (one of our BBANs);
  `CounterpartAccount` is the remitter."
  [config sort-code e2e-id body]
  (let [{:keys [bban amount currency reference debtor-name]} body]
    (fire config
          sort-code
          "InboundHeldTransaction"
          {:TimestampCreated (now-rfc3339)
           :Scheme "FasterPayments"
           :Account {:BBAN bban}
           :CounterpartAccount {:OwnerName (or debtor-name "Simulated Debtor")}
           :TransactionAmount amount
           :CurrencyCode (or currency "GBP")
           :PaymentReference (or reference "")
           :EndToEndTransactionId e2e-id})))

(defn fire-transaction-rejected
  "Fires a TransactionRejected webhook for a held outbound transaction
  that ClearBank declined: funds are returned (`IsReturn true`) and the
  HOPRJ cancellation code is raised."
  [config sort-code e2e-id]
  (fire config
        sort-code
        "TransactionRejected"
        {:TransactionId (str (uuidv7))
         :Status "Rejected"
         :Scheme "FasterPayments"
         :EndToEndTransactionId e2e-id
         :CancellationCode "HOPRJ"
         :CancellationReason "Held transaction declined"
         :TimestampModified (now-rfc3339)
         :DebitCreditCode "Debit"
         :IsReturn true
         :Account {}
         :CounterpartAccount {}}))

(defn fire-inbound-transaction-returned
  "Fires a TransactionRejected (Credit) webhook for a held inbound ClearBank
  declined — the funds go back to the remitting bank. `Account` carries
  the recipient's BBAN when the simulate body has one."
  [config sort-code e2e-id body]
  (let [{:keys [bban]} body]
    (fire config
          sort-code
          "TransactionRejected"
          {:TransactionId (str (uuidv7))
           :Status "Rejected"
           :Scheme "FasterPayments"
           :EndToEndTransactionId e2e-id
           :CancellationCode "RR04"
           :CancellationReason "Held inbound declined"
           :TimestampModified (now-rfc3339)
           :DebitCreditCode "Credit"
           :IsReturn true
           :Account (assoc-some {} :BBAN bban)
           :CounterpartAccount {}})))

(defn fire-payment-message-assessment-failed
  "Fires a PaymentMessageAssessmentFailed webhook — ClearBank rejected the
  payment during pre-settlement message assessment. `reasons` is the list
  of scheme assessment failures for this instruction."
  [config sort-code e2e-id reasons]
  (fire config
        sort-code
        "PaymentMessageAssessmentFailed"
        {:MessageId (str (uuidv7))
         :PaymentMethodType "FasterPayments"
         :AssessmentFailure [{:EndToEndId e2e-id
                              :Reasons reasons}]
         :AccountIdentification {:Debtor {}
                                 :Creditors []}}))

(defn fire-inbound-cop-request
  [config sort-code request-id body]
  (let [{:keys [accountDetails accountHolderName
                accountType requestingInstitution]}
        body
        {:keys [sortCode accountNumber]} accountDetails]
    (fire config
          sort-code
          "InboundCopRequestReceived"
          {:RequestId request-id
           :RequestingInstitution (or requestingInstitution "")
           :AccountHolderName accountHolderName
           :ProductType accountType
           :AccountDetails {:SortCode sortCode
                            :AccountNumber accountNumber}
           :TimestampCreated (now-rfc3339)})))
