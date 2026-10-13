(ns com.repldriven.queenswood.payee-check.domain
  (:require
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^{:doc "How long a check stands after it is made, in milliseconds."}
     lifetime-ms
  (* 24 60 60 1000))

(def ^{:doc "The result of a check its provider gave no answer to."} unavailable
  {:match-result :match-result-unavailable
   :reason-code "ACNS"
   :reason "CoP service unavailable"})

(defn- sanitize-result
  [result]
  {:match-result (:match-result result)
   :actual-name (or (:actual-name result) "")
   :reason-code (or (:reason-code result) "")
   :reason (or (:reason result) "")})

(defn- check-limit
  [aggregates policies]
  (policy/check-limit policies
                      :payee-check
                      {:aggregate :count
                       :window :time-window-daily
                       :value (inc (get-in aggregates
                                           [:payee-check
                                            #{:bank-id :checked-on}]))}))

(defn new-check
  [bank-id request actor checked-on aggregates policies]
  (let-nom>
    [_ (policy/check-capability policies
                                :payee-check
                                {:action :payee-check-action-check})
     _ (check-limit aggregates policies)]
    {:bank-id bank-id
     :check-id (utility/generate-id "chk")
     :request request
     :result (sanitize-result unavailable)
     :checked-on checked-on
     :created-at (utility/now)
     :created-by (select-keys actor [:kind :principal-id])}))

(defn answered
  [check result]
  (assoc check :result (sanitize-result result)))

(defn expires-at
  [check]
  (+ (:created-at check) lifetime-ms))
