(ns com.repldriven.queenswood.demo-digital-bank-api.auth
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.errors :as errors]

    [com.repldriven.queenswood.demo-digital-bank.interface :as bank]

    [com.repldriven.mono.error.interface :as error]

    [sieppari.context :as sc]

    [clojure.string :as str]))

(defn token
  "The bearer the request carries, or nil."
  [request]
  (some-> (get-in request [:headers "authorization"])
          (str/split #" " 2)
          (as-> parts (when (= "Bearer" (first parts)) (second parts)))))

(def session
  "Resolves the bearer to the customer, put on the request as
  `:customer`, or answers 401."
  {:name ::session
   :enter (fn [ctx]
            (let [{:keys [request]} ctx
                  {:keys [bank]} request
                  customer (bank/authenticate bank (token request))]
              (if (error/anomaly? customer)
                (sc/terminate ctx (errors/anomaly->response customer))
                (assoc-in ctx [:request :customer] customer))))})
