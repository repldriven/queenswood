(ns com.repldriven.queenswood.payee-check.domain
  (:require
    [com.repldriven.mono.utility.interface :as utility]))

(def ^{:doc "How long a check stands after it is made, in milliseconds."}
     lifetime-ms
  (* 24 60 60 1000))

(defn- sanitize-result
  [result]
  {:match-result (:match-result result)
   :actual-name (or (:actual-name result) "")
   :reason-code (or (:reason-code result) "")
   :reason (or (:reason result) "")})

(defn new-check
  [bank-id request result actor]
  {:bank-id bank-id
   :check-id (utility/generate-id "chk")
   :request request
   :result (sanitize-result result)
   :created-at (utility/now)
   :created-by (select-keys actor [:kind :principal-id])})

(defn expires-at
  [check]
  (+ (:created-at check) lifetime-ms))
