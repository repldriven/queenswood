(ns com.repldriven.queenswood.form3-adapter.provider
  (:require
    [com.repldriven.queenswood.payment-provider.interface :as
     payment-provider]))

(def ^:private carries
  {:schemes #{"fps"}
   :addresses #{"scan"}
   :balances #{"pooled"}
   :payee-check #{"outbound"}
   :inbound #{"admitted"}
   :returns #{"inbound"}
   :screening #{"bank"}})

(defn check
  [declaration]
  (payment-provider/check declaration carries))
