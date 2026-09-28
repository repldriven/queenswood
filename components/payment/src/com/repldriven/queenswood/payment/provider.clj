(ns com.repldriven.queenswood.payment.provider
  (:require
    [com.repldriven.queenswood.bank-query.interface :as bank-query]
    [com.repldriven.queenswood.payment-provider.interface :as
     payment-provider]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- entry
  [config txn bank-id]
  (when-let [providers (:payment-providers config)]
    (let-nom> [bank (bank-query/find-bank txn bank-id)]
      (payment-provider/for-bank providers bank))))

(defn declaration
  [config txn bank-id]
  (error/nom-> (entry config txn bank-id)
               :declaration))

(defn payment-command-channel
  [config txn bank-id]
  (error/nom-> (entry config txn bank-id)
               :payment-command-channel))
