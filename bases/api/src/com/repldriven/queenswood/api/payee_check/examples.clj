(ns com.repldriven.queenswood.api.payee-check.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :refer
     [examples-registry]]))

(def PayeeCheckRequest
  {:creditor-name "Arthur Dent"
   :account {:sort-code "040062" :account-number "12345678"}
   :account-type :personal})

(def CheckId "chk.01kprbmgcj35ptc8npmybhh4sa")

(def PayeeCheck
  {:check-id CheckId
   :request {:creditor-name "Arthur Dent"
             :account {:sort-code "040062" :account-number "12345678"}
             :account-type :personal}
   :result {:match-result :close-match
            :actual-name "Jane A Doe"
            :reason-code "PANM"
            :reason "Partial name match"}
   :created-at "2026-04-20T14:23:05.123Z"
   :expires-at "2026-04-20T14:38:05.123Z"})

(def PayeeCheckList
  {:items [PayeeCheck] :links {:next "/v1/payee-checks?page[after]=djE6..."}})

(def PayeeCheckNotFound
  {:value {:title "REJECTED"
           :type ":payee-check/not-found"
           :status 404
           :detail "Payee check not found"}})

(def registry
  (examples-registry [#'PayeeCheck #'PayeeCheckList #'PayeeCheckNotFound]))
