(ns com.repldriven.queenswood.intent-poller.operations
  (:require
    [com.repldriven.mono.error.interface :as error]))

(defmulti operation (fn [adapter o] [adapter o]))

(defmethod operation :default
  [adapter o]
  (error/fail :intent-poller/unknown-operation
              {:message "No operation is registered for the intent's kind"
               :adapter adapter
               :operation o}))

(defmacro defoperations
  [adapter operation-map]
  `(do ~@(for [[o f] operation-map]
           `(defmethod operation [~adapter ~o] [~'_ ~'_] ~f))))
