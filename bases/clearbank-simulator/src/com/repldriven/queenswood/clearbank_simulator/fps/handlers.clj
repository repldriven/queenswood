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
          reference (get-in remittanceInformation
                            [:unstructured
                             :additionalReferenceInformation
                             :reference])
          {:keys [instructedAmount currency]} amount
          config {:webhooks webhooks}]
      (if (= submission-refused-sort-code (sort-code-of creditor-bban))
        {:status 202
         :body
         {:transactions
          [{:endToEndIdentification endToEndIdentification
            :response "Rejected"}]
          :halLinks []}}
        (do
          (future
           (when (pos? (or webhook-delay-ms 0))
             (Thread/sleep webhook-delay-ms))
           (cond
            (= assessment-fail-sort-code (sort-code-of creditor-bban))
            ;; ClearBank rejects the payment at message assessment, before
            ;; any settle/held — an unreachable creditor sort code here.
            (webhook/fire-payment-message-assessment-failed
             config
             sort-code
             endToEndIdentification
             ["No internal account located for Creditor"])

            (= held-magic-name name)
            ;; ClearBank holds the payment for screening, then (in the sim)
            ;; declines it: the held webhook lands first, the decline —
            ;; funds returned, HOPRJ — follows after the same delay.
            (do
              (webhook/fire-outbound-held-transaction
               config
               sort-code
               endToEndIdentification
               {:amount instructedAmount
                :currency currency
                :reference reference
                :creditor-bban creditor-bban})
              (when (pos? (or webhook-delay-ms 0))
                (Thread/sleep webhook-delay-ms))
              (webhook/fire-transaction-rejected
               config
               sort-code
               endToEndIdentification))

            :else
            (do
              (webhook/fire-transaction-settled
               config
               sort-code
               endToEndIdentification
               :debit
               {:amount instructedAmount
                :currency currency
                :reference reference})
              (when (pos? (or webhook-delay-ms 0))
                (Thread/sleep webhook-delay-ms))
              (webhook/fire-transaction-settled
               config
               sort-code
               (str (uuidv7))
               :credit
               {:amount instructedAmount
                :currency currency
                :creditor-bban creditor-bban
                :debtor-name name
                :reference reference}))))
          {:status 202
           :body
           {:transactions
            [{:endToEndIdentification endToEndIdentification
              :response "Accepted"}]
            :halLinks []}})))))
