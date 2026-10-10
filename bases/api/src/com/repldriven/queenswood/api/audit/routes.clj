(ns com.repldriven.queenswood.api.audit.routes
  (:require
    [com.repldriven.queenswood.api.audit.queries :as queries]

    [com.repldriven.queenswood.api.shared.interceptors :as shared.interceptors]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer [ErrorExamples]]
    [com.repldriven.queenswood.audit-api.interface :as audit-api]
    [com.repldriven.queenswood.bank-api.interface :refer [BankUnnamed]]))

;; The bank the `Bank-Id` header names: a member's own, or any an
;; operator names. `named-bank` refuses an operator who names none.
(def routes
  [
   ["/bank/audit-events"
    {:openapi {:tags ["Audit"]
               :security [{"bearerAuth" ["org:viewer"]}
                          {"bearerAuth" ["admin"]}]}
     :interceptors [shared.interceptors/named-bank]
     :get {:summary "List the bank's audit events"
           :openapi {:operationId "ListAuditEvents"
                     :description
                     (str "The audit log of the bank the `Bank-Id` header "
                          "names, newest first: every act a person, the "
                          "bank or an operator performed on its records, "
                          "each naming the record, who performed it, the "
                          "request that asked for it and any reason given. "
                          "`kind` lists only the kinds of act it names.")
                     :parameters ^:replace
                                 [shared.parameters/ref-audit-event-kind
                                  shared.parameters/ref-page
                                  shared.parameters/ref-bank-id-header]}
           :parameters {:query audit-api/audit-event-query}
           :responses {200 {:description "One page of the bank's audit events."
                            :body [:ref "AuditEventList"]}
                       403 (ErrorExamples [#'BankUnnamed])}
           :handler queries/list-audit-events}}]])

