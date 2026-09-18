(ns com.repldriven.queenswood.clearbank-simulator.fps.handlers
  (:require
    [com.repldriven.queenswood.clearbank-simulator.webhook
     :as webhook]

    [com.repldriven.mono.utility.interface :refer [uuidv7]]))

;; ClearBank's documented sandbox trigger: a Faster Payment whose creditor
;; Name is this value is held for screening (OutboundHeldTransaction). The
;; sim always declines a held outbound.
(def ^:private held-magic-name "6a41a29eafcf455493")

;; Sim convention (ClearBank documents no sandbox trigger): a creditor BBAN
;; with this sort code fails pre-settlement message assessment
;; (PaymentMessageAssessmentFailed), as an unreachable sort code would.
(def ^:private assessment-fail-sort-code "000000")

;; Sim convention: a creditor BBAN with this sort code is refused in the
;; submission response itself (response Rejected), and no webhook fires.
;; Not 000001, which the first bank created is allocated.
(def ^:private submission-refused-sort-code "999998")

(defn- sort-code-of
  [bban]
  (when (and bban (>= (count bban) 6)) (subs bban 0 6)))

(defn- submission-response
  [end-to-end-id response]
  {:status 202
   :body {:transactions [{:endToEndIdentification end-to-end-id
                          :response response}]
          :halLinks []}})

(defn- fire-webhooks
  [{:keys [config sort-code webhook-delay-ms end-to-end-id creditor-sort-code
           creditor-name creditor-bban amount currency reference]}]
  (let [pause (fn []
                (when (pos? (or webhook-delay-ms 0))
                  (Thread/sleep webhook-delay-ms)))]
    (pause)
    (cond
     (= assessment-fail-sort-code creditor-sort-code)
     ;; ClearBank rejects the payment at message assessment, before
     ;; any settle/held — an unreachable creditor sort code here.
     (webhook/fire-payment-message-assessment-failed
      config
      sort-code
      end-to-end-id
      ["No internal account located for Creditor"])

     (= held-magic-name creditor-name)
     ;; ClearBank holds the payment for screening, then (in the sim)
     ;; declines it: the held webhook lands first, the decline —
     ;; funds returned, HOPRJ — follows after the same delay.
     (do
       (webhook/fire-outbound-held-transaction
        config
        sort-code
        end-to-end-id
        {:amount amount
         :currency currency
         :reference reference
         :creditor-bban creditor-bban})
       (pause)
       (webhook/fire-transaction-rejected config sort-code end-to-end-id))

     :else
     (do
       (webhook/fire-transaction-settled
        config
        sort-code
        end-to-end-id
        :debit
        {:amount amount :currency currency :reference reference})
       (pause)
       (webhook/fire-transaction-settled
        config
        sort-code
        (str (uuidv7))
        :credit
        {:amount amount
         :currency currency
         :creditor-bban creditor-bban
         :debtor-name creditor-name
         :reference reference})))))

(defn payment
  [_config]
  (fn [request]
    (let [{:keys [webhooks sort-code webhook-delay-ms parameters]}
          request
          {:keys [body]} parameters
          {:keys [paymentInstructions]} body
          instruction (first paymentInstructions)
          {:keys [creditTransfers]} instruction
          transfer (first creditTransfers)
          {:keys [paymentIdentification creditor
                  creditorAccount amount
                  remittanceInformation]}
          transfer
          {:keys [endToEndIdentification]} paymentIdentification
          {:keys [name]} creditor
          creditor-bban (get-in creditorAccount
                                [:identification :other
                                 :identification])
          creditor-sort-code (sort-code-of creditor-bban)
          reference (get-in remittanceInformation
                            [:unstructured
                             :additionalReferenceInformation
                             :reference])
          {:keys [instructedAmount currency]} amount]
      (if (= submission-refused-sort-code creditor-sort-code)
        (submission-response endToEndIdentification "Rejected")
        (do (future
             (fire-webhooks {:config {:webhooks webhooks}
                             :sort-code sort-code
                             :webhook-delay-ms webhook-delay-ms
                             :end-to-end-id endToEndIdentification
                             :creditor-sort-code creditor-sort-code
                             :creditor-name name
                             :creditor-bban creditor-bban
                             :amount instructedAmount
                             :currency currency
                             :reference reference}))
            (submission-response endToEndIdentification "Accepted"))))))
