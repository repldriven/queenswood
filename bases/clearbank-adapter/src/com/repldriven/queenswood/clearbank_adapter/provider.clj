(ns com.repldriven.queenswood.clearbank-adapter.provider
  (:require
    [com.repldriven.mono.error.interface :as error]))

(def ^:private carries
  {:schemes #{"fps"}
   :addresses #{"scan"}
   :balances #{"pooled"}
   :payee-check #{"outbound" "inbound"}})

(defn- uncovered
  [declaration]
  (into {}
        (keep (fn [[k supported]]
                (let [declared (get declaration k)
                      missing (remove supported
                                      (if (coll? declared)
                                        declared
                                        (some-> declared
                                                vector)))]
                  (when (seq missing) [k (vec missing)]))))
        carries))

(defn check
  [declaration]
  (let [missing (uncovered declaration)]
    (when (seq missing)
      (error/fail :payment/unsupported-declaration
                  {:message
                   "The payment provider declaration asks more than it carries"
                   :uncovered missing}))))
