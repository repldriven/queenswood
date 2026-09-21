(ns com.repldriven.queenswood.demo-digital-bank-api.payment.handlers
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.errors :as errors]
    [com.repldriven.queenswood.demo-digital-bank-api.shared.request :as request]

    [com.repldriven.queenswood.demo-digital-bank.interface :as bank]))

(defn check-payee
  [request]
  (let [{:keys [bank customer]} request]
    (errors/respond 200
                    (bank/check-payee bank customer (request/body request)))))

(defn submit
  [request]
  (let [{:keys [bank customer]} request]
    (errors/respond 201
                    (bank/submit-payment bank
                                         customer
                                         (request/client-key request)
                                         (request/body request)))))

(defn transfer
  [request]
  (let [{:keys [bank customer]} request]
    (errors/respond 201
                    (bank/transfer bank
                                   customer
                                   (request/client-key request)
                                   (request/body request)))))
