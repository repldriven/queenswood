(ns com.repldriven.queenswood.modulr-adapter.provider
  (:require
    [com.repldriven.queenswood.payment-provider.interface :as
     payment-provider]))

(def ^:private carries
  {:schemes #{"fps"}
   :addresses #{"scan"}
   :balances #{"per-account"}
   :payee-check #{"outbound"}
   :inbound #{"notified"}
   :returns #{}
   :screening #{"provider"}})

(defn check
  [declaration]
  (payment-provider/check declaration carries))
