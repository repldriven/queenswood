(ns com.repldriven.queenswood.payment-api.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :refer
     [examples-registry]]))

(def PaymentNotFound
  {:value {:title "REJECTED"
           :type "payment/not-found"
           :status 404
           :detail "Payment not found"}})

(def BalanceNotFound
  {:value {:title "REJECTED"
           :type ":balance/not-found"
           :status 404
           :detail "Balance not found"}})

(def InvalidAmount
  {:value {:title "REJECTED"
           :type ":transaction/invalid-amount"
           :status 422
           :detail "Transaction amount must be positive"}})

(def PaymentId "pmt.01kprbmgcj35ptc8npmybhh4s5")

(def SubmitInternalPaymentRequest
  {:debtor-account-id "acc.01kprbmgcj35ptc8npmybhh4s8"
   :creditor-account-id "acc.01kprbmgcj35ptc8npmybhh4s9"
   :currency "GBP"
   :amount 1000
   :reference "Internal transfer"})

(def InternalPayment
  {:payment-id PaymentId
   :bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :debtor-account-id "acc.01kprbmgcj35ptc8npmybhh4s8"
   :creditor-account-id "acc.01kprbmgcj35ptc8npmybhh4s9"
   :currency "GBP"
   :amount 1000
   :transaction-id "txn.01kprbmgcj35ptc8npmybhh4sb"
   :reference "Internal transfer"
   :business-day "2025-01-01"
   :created-at "2025-01-01T00:00:00Z"
   :updated-at "2025-01-01T00:00:00Z"})

(def SubmitOutboundPaymentRequest
  {:debtor-account-id "acc.01kprbmgcj35ptc8npmybhh4s8"
   :creditor-bban "04000412345678"
   :creditor-name "Arthur Dent"
   :currency "GBP"
   :amount 500
   :scheme "fps"
   :reference "Invoice 123"})

(def OutboundPayment
  {:payment-id "pmt.01kprbmgcj35ptc8npmybhh4s6"
   :bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :scheme "fps"
   :debtor-account-id "acc.01kprbmgcj35ptc8npmybhh4s8"
   :creditor-bban "04000412345678"
   :creditor-name "Arthur Dent"
   :currency "GBP"
   :amount 500
   :payment-status :outbound-payment-status-pending
   :transaction-id "txn.01kprbmgcj35ptc8npmybhh4sb"
   :reference "Invoice 123"
   :business-day "2025-01-01"
   :created-at "2025-01-01T00:00:00Z"
   :updated-at "2025-01-01T00:00:00Z"})

(def InboundPayment
  {:payment-id "pmt.01kprbmgcj35ptc8npmybhh4t0"
   :bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :scheme "FasterPayments"
   :scheme-transaction-id "8f3e2a1c-6b4d-4e7f-9a2b-1c3d5e7f9a0b"
   :end-to-end-id "E2E-20250101-0001"
   :creditor-account-id "acc.01kprbmgcj35ptc8npmybhh4s9"
   :currency "GBP"
   :amount 2500
   :payment-status "settled"
   :transaction-id "txn.01kprbmgcj35ptc8npmybhh4t1"
   :debtor-name "Ford Prefect"
   :reference "Rent"
   :business-day "2025-01-01"
   :created-at "2025-01-01T00:00:00Z"
   :updated-at "2025-01-01T00:00:00Z"})

(def SettledInboundPayment
  {:summary "Settled to the creditor's account" :value InboundPayment})

(def SuspendedInboundPayment
  {:summary "Matched no account and parked in suspense"
   :value (-> InboundPayment
              (dissoc :creditor-account-id)
              (assoc :payment-id "pmt.01kprbmgcj35ptc8npmybhh4t2"
                     :scheme-transaction-id
                     "3c9d7b2e-1a4f-4c6d-8e0b-2f4a6c8e0d1b"
                     :end-to-end-id "E2E-20250101-0002"
                     :payment-status "suspended"
                     :transaction-id "txn.01kprbmgcj35ptc8npmybhh4t3"))})

(def HeldInboundPayment
  {:summary "Held at the scheme, with nothing posted"
   :value (-> InboundPayment
              (dissoc :transaction-id)
              (assoc :payment-id "pmt.01kprbmgcj35ptc8npmybhh4t4"
                     :scheme-transaction-id
                     "held-01943b6e-7a2c-7f3d-9c1e-5b2a4d6e8f10"
                     :end-to-end-id "E2E-20250101-0003"
                     :payment-status "held"))})

(def ReturnedInboundPayment
  {:summary "Declined while held and returned to the remitter"
   :value (assoc (:value HeldInboundPayment)
                 :payment-status "returned"
                 :updated-at "2025-01-01T00:05:00Z")})

(def InboundPaymentList
  {:summary "Suspended inbound payments, newest first"
   :value {:items [(:value SuspendedInboundPayment)]
           :links {:next (str "/v1/payments/inbound?status=suspended"
                              "&page[after]=djE6...&page[size]=20")}}})

(def registry
  (examples-registry [#'PaymentNotFound #'BalanceNotFound #'InvalidAmount
                      #'SettledInboundPayment #'SuspendedInboundPayment
                      #'HeldInboundPayment #'ReturnedInboundPayment
                      #'InboundPaymentList]))
