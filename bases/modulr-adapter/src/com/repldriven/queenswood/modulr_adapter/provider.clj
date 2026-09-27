(ns com.repldriven.queenswood.modulr-adapter.provider
  (:require
    [com.repldriven.mono.error.interface :as error]))

(def ^:private carries
  {:schemes #{"fps"}
   :addresses #{"scan"}
   :balances #{"per-account"}
   :payee-check #{"outbound"}})

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
