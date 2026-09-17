(ns com.repldriven.queenswood.clearbank-adapter.clearbank
  (:require
    [com.repldriven.mono.json.interface :as json]))

(defn ->fps-body
  "Build the ClearBank FPS request JSON body from a submit-payment
  command. `endToEndIdentification` is ClearBank's idempotency key, so a
  retried POST of this exact body is safe. Pure — the outbound relay
  makes the actual HTTP call."
  [data]
  (let [{:keys [payment-id end-to-end-id debtor-bban
                creditor-bban creditor-name amount
                currency reference]}
        data]
    (json/write-str
     {:paymentInstructions
      [{:debtorAccount
        {:identification
         {:other
          {:identification debtor-bban
           :schemeName {:code "BBAN"}}}}
        :paymentInstructionIdentification payment-id
        :paymentTypeCode "SIP"
        :creditTransfers
        [{:paymentIdentification
          {:instructionIdentification payment-id
           :endToEndIdentification end-to-end-id}
          :amount
          {:currency currency
           :instructedAmount (.movePointLeft (BigDecimal/valueOf (long amount))
                                             2)}
          :creditor
          {:name creditor-name}
          :creditorAccount
          {:identification
           {:other
            {:identification creditor-bban
             :schemeName {:code "BBAN"}}}}
          :remittanceInformation
          {:unstructured
           {:additionalReferenceInformation
            {:reference (or reference "")}}}}]}]})))
