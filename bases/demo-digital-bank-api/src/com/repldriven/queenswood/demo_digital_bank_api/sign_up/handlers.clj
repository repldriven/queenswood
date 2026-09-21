(ns com.repldriven.queenswood.demo-digital-bank-api.sign-up.handlers
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.errors :as errors]
    [com.repldriven.queenswood.demo-digital-bank-api.shared.request :as request]

    [com.repldriven.queenswood.demo-digital-bank.interface :as bank]))

(defn- sign-up-id
  [request]
  (get-in request [:parameters :path :sign-up-id]))

(defn start
  [request]
  (let [{:keys [bank]} request]
    (errors/respond 201 (bank/start-sign-up bank (request/body request)))))

(defn verify-code
  [request]
  (let [{:keys [bank]} request]
    (errors/respond 200
                    (bank/verify-code bank
                                      (sign-up-id request)
                                      (request/body request)))))

(defn register-details
  [request]
  (let [{:keys [bank]} request]
    (errors/respond 200
                    (bank/register-details bank
                                           (sign-up-id request)
                                           (request/body request)))))

(defn choose-passcode
  [request]
  (let [{:keys [bank]} request]
    (errors/respond 201
                    (bank/choose-passcode bank
                                          (sign-up-id request)
                                          (request/body request)))))
