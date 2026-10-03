(ns com.repldriven.queenswood.registrar.subscriptions
  (:require
    [com.repldriven.mono.error.interface :as error]))

(defmulti subscriptions (fn [adapter] adapter))

(defmethod subscriptions :default
  [adapter]
  (error/fail :registrar/unknown-adapter
              {:message "No subscriptions are registered for the adapter"
               :adapter adapter}))

(defmacro defsubscriptions
  [adapter subscription-map]
  `(defmethod subscriptions ~adapter [~'_] ~subscription-map))
