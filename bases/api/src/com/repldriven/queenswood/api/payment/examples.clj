(ns com.repldriven.queenswood.api.payment.examples
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

(def AlreadySubmitted
  {:value {:title "REJECTED"
           :type ":payment/already-submitted"
           :status 409
           :detail "Payment already submitted"}})

(def InvalidAmount
  {:value {:title "REJECTED"
           :type ":transaction/invalid-amount"
           :status 422
           :detail "Transaction amount must be positive"}})

(def registry
  (examples-registry [#'PaymentNotFound #'BalanceNotFound #'AlreadySubmitted
                      #'InvalidAmount]))

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
