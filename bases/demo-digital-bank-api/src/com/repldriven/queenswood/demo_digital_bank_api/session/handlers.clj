(ns com.repldriven.queenswood.demo-digital-bank-api.session.handlers
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.errors :as errors]
    [com.repldriven.queenswood.demo-digital-bank-api.shared.request :as request]

    [com.repldriven.queenswood.demo-digital-bank.interface :as bank]))

(defn sign-in
  [request]
  (let [{:keys [bank]} request]
    (errors/respond 201 (bank/sign-in bank (request/body request)))))

(defn sign-out
  [request]
  (let [{:keys [bank credential]} request
        result (bank/sign-out bank credential)]
    (if (nil? result) {:status 204} (errors/respond 204 result))))
