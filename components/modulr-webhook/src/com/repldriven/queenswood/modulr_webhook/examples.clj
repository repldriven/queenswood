(ns com.repldriven.queenswood.modulr-webhook.examples)

(def PartyIdentifier
  {:Type "SCAN" :SortCode "040010" :AccountNumber "00001457"})

(def PartyDetail {:Name "Arthur Dent" :Identifier PartyIdentifier})

(def SchemeInfo {:Id "FP00009O144093771020200101826070123"})

(def PayinWebhook
  {:EventName "PAYIN"
   :EventId "7a1b81bc-5d5c-4045-9099-66b6ea841969"
   :EventTime "2026-09-27T16:38:06+0000"
   :Type "PI_FAST"
   :PaymentId "P12000MWF8"
   :TransactionId "T12000N5TJ"
   :AccountId "A120C8D3"
   :Amount "6.00"
   :Currency "GBP"
   :DateTime "2026-09-27T16:38:06+0000"
   :PayerName "Ford Prefect"
   :Payer {:Name "Ford Prefect"
           :Identifier
           {:Type "SCAN" :SortCode "203002" :AccountNumber "00004588"}}
   :Payee PartyDetail
   :PaymentReference "Towel"
   :SchemeInfo SchemeInfo})

(def PayoutWebhook
  {:EventName "PAYOUT"
   :EventId "46915325-a6db-46c5-a3d8-606f35bde2ef"
   :EventTime "2026-09-27T10:53:38+0000"
   :Status "PROCESSED"
   :PaymentId "P12000MWFB"
   :TransactionId "T12000N5TN"
   :TransactionType "PO_FAST"
   :AccountId "A120C8D3"
   :Amount "40.00"
   :DateTime "2026-09-27T10:53:38+0000"
   :Reference "Sent from Arthur Dent"
   :ExternalReference "pmt.01k6a3z9x0000000000000000"
   :SchemeInfo {:Id "MODULO00P12000MWFB020200101826040010"
                :ResponseCode "0000"}})

(def ComplianceStatusWebhook
  {:EventName "PAYMENTCOMPLIANCESTATUS"
   :EventId "a35aec8f-4666-4ad1-9266-c8ce46775d4b"
   :EventTime "2026-09-27T11:43:52+0000"
   :AccountBid "A110Z2CX"
   :PaymentBid "P1100UHWB1"
   :CustomerBid "C110QUFV"
   :ComplianceStatus "HELD"})

(def WebhookRejected
  {:type ":payment-webhook/invalid-signature"
   :title "UNAUTHORIZED"
   :status 401
   :detail "the request's signature does not verify"})
