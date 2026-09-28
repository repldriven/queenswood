(ns com.repldriven.queenswood.payment-provider.core
  (:require
    [com.repldriven.mono.error.interface :as error]))

(def defaults {:inbound "notified" :returns [] :screening "provider"})

(defn declared
  [declaration]
  (merge defaults declaration))

(defn- values
  [v]
  (cond
   (nil? v)
   []

   (coll? v)
   v

   :else
   [v]))

(defn- uncovered
  [declaration carries]
  (let [declaration (declared declaration)]
    (into {}
          (keep (fn [[k supported]]
                  (let [missing (vec (remove supported
                                             (values (get declaration k))))]
                    (when (seq missing) [k missing]))))
          carries)))

(defn check
  [declaration carries]
  (let [missing (uncovered declaration carries)]
    (when (seq missing)
      (error/fail :payment/unsupported-declaration
                  {:message
                   "The payment provider declaration asks more than it carries"
                   :uncovered missing}))))
