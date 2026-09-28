(ns com.repldriven.queenswood.clearbank-adapter.provider
  (:require
    [com.repldriven.queenswood.payment-provider.interface :as
     payment-provider]))

(def ^:private carries
  {:schemes #{"fps"}
   :addresses #{"scan"}
   :balances #{"pooled"}
   :payee-check #{"outbound" "inbound"}
   :inbound #{"notified"}
   :returns #{}
   :screening #{"provider"}})

(defn check
  [declaration]
  (payment-provider/check declaration carries))
