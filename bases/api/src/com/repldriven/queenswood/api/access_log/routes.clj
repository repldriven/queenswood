(ns com.repldriven.queenswood.api.access-log.routes
  (:require
    [com.repldriven.queenswood.api.access-log.queries :as queries]

    [com.repldriven.queenswood.api.shared.interceptors :as shared.interceptors]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.access-log-api.interface :as access-log-api]
    [com.repldriven.queenswood.api-schema.interface :refer [ErrorExamples]]
    [com.repldriven.queenswood.bank-api.interface :refer [BankUnnamed]]))

;; The bank the `Bank-Id` header names: a member's own, or any an
;; operator names. `named-bank` refuses an operator who names none.
(def routes
  [
   ["/bank/access-log"
    {:openapi {:tags ["Access log"]
               :security [{"bearerAuth" ["org:viewer"]}
                          {"bearerAuth" ["admin"]}]}
     :interceptors [shared.interceptors/named-bank]
     :get {:summary "List the bank's access entries"
           :openapi {:operationId "ListAccessEntries"
                     :description
                     (str "The access log of the bank the `Bank-Id` header "
                          "names, newest first: every call to the API for "
                          "the bank by a caller authenticated for it, with "
                          "who called, the route and its ids, what it "
                          "answered and how long it took. `ref` lists only "
                          "the calls that carried it as their request id, "
                          "idempotency key or correlation id, and `tag` "
                          "only the calls to the operations of the tags it "
                          "names; with `ref`, a page may hold fewer calls "
                          "than its size. A call that "
                          "named no bank, was not authenticated or failed "
                          "validation is not logged, and entries expire "
                          "after the period the installation sets.")
                     :parameters ^:replace
                                 [shared.parameters/ref-access-ref
                                  shared.parameters/ref-access-tag
                                  shared.parameters/ref-page
                                  shared.parameters/ref-bank-id-header]}
           :parameters {:query access-log-api/access-log-query}
           :responses {200 {:description
                            "One page of the bank's access entries."
                            :body [:ref "AccessEntryList"]}
                       403 (ErrorExamples [#'BankUnnamed])}
           :handler queries/list-access-entries}}]])
