(ns com.repldriven.queenswood.demo-digital-bank-api.account.handlers
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.errors :as errors]
    [com.repldriven.queenswood.demo-digital-bank-api.shared.request :as request]

    [com.repldriven.queenswood.demo-digital-bank.interface :as bank]))

(defn open
  [request]
  (let [{:keys [bank customer]} request]
    (errors/respond 201
                    (bank/open-account bank
                                       customer
                                       (request/client-key request)
                                       (request/body request)))))
