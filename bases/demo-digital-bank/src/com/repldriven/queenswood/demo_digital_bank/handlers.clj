(ns com.repldriven.queenswood.demo-digital-bank.handlers
  (:require
    [com.repldriven.queenswood.demo-digital-bank.auth :as auth]
    [com.repldriven.queenswood.demo-digital-bank.errors :as errors]

    [com.repldriven.queenswood.demo-digital-bank-core.interface :as bank]))

(defn- sign-up-id
  [request]
  (get-in request [:parameters :path :sign-up-id]))

(defn- body [request] (get-in request [:parameters :body]))

(defn start-sign-up
  [request]
  (let [{:keys [bank]} request]
    (errors/respond 201 (bank/start-sign-up bank (body request)))))

(defn verify-code
  [request]
  (let [{:keys [bank]} request]
    (errors/respond
     200
     (bank/verify-code bank (sign-up-id request) (body request)))))

(defn register-details
  [request]
  (let [{:keys [bank]} request]
    (errors/respond 200
                    (bank/register-details bank
                                           (sign-up-id request)
                                           (body request)))))

(defn choose-passcode
  [request]
  (let [{:keys [bank]} request]
    (errors/respond 201
                    (bank/choose-passcode bank
                                          (sign-up-id request)
                                          (body request)))))

(defn sign-in
  [request]
  (let [{:keys [bank]} request]
    (errors/respond 201 (bank/sign-in bank (body request)))))

(defn sign-out
  [request]
  (let [{:keys [bank]} request
        result (bank/sign-out bank (auth/token request))]
    (if (nil? result) {:status 204} (errors/respond 204 result))))

(defn me
  [request]
  (let [{:keys [bank customer]} request]
    (errors/respond 200 (bank/me bank customer))))
