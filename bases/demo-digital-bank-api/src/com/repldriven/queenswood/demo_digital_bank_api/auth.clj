(ns com.repldriven.queenswood.demo-digital-bank-api.auth
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.errors :as errors]

    [com.repldriven.queenswood.demo-digital-bank.interface :as bank]

    [com.repldriven.mono.error.interface :as error]

    [sieppari.context :as sc]))

(def credential->customer
  "Resolves the `:credential` `server/credential` put on the request to
  the customer whose session it is, as `:customer` for the handlers and
  as `:auth-claims` for `server/require-scopes`. A session that is
  missing or has expired sets nothing, and the gate answers 401 in the
  route data's `:unauthorized`; a store the bank cannot reach is answered
  as the fault it is."
  {:name ::credential->customer
   :enter (fn [ctx]
            (let [{:keys [request]} ctx
                  {:keys [bank credential]} request
                  customer (when credential
                             (bank/authenticate bank credential))]
              (cond (nil? customer)
                    ctx
                    (error/unauthorized? customer)
                    ctx
                    (error/anomaly? customer)
                    (sc/terminate ctx (errors/anomaly->response customer))
                    :else
                    (update ctx
                            :request assoc
                            :customer customer
                            :auth-claims customer))))})
