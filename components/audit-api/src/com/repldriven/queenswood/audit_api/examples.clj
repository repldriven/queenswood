(ns com.repldriven.queenswood.audit-api.examples
  (:require
    [com.repldriven.queenswood.me-api.interface :as me-api]))

(def AuditEventId "aev.01kprbmgcj35ptc8npmybhh4sn")

(def ^:private member-id "mem.01kprbpdwa9q5n2t7vwsx84a3n")

(def ^:private member-user-id "usr.01kprbmgcj35ptc8npmybhh4sp")

(def ^:private Actor
  {:kind :member :principal-id me-api/UserId :name "Ada Lovelace"})

(def AuditEvent
  {:audit-event-id AuditEventId
   :bank-id me-api/BankId
   :kind :member-role-changed
   :subject-kind :member
   :subject-id member-id
   :actor Actor
   :request-id "01a124c5-a4c6-7578-92a9-fc765876a148"
   :subject-user-id member-user-id
   :subject-name "Charles Babbage"
   :member-id member-id
   :role-before :developer
   :role-after :admin
   :reason "Leads the payments team"
   :occurred-at "2026-06-01T09:00:00Z"})

(def AuditEventList
  {:items [AuditEvent]
   :links {:next (str "/v1/bank/audit-events?page[after]="
                      "djE6YWV2LjAxa3ByYm1nY2ozNXB0YzhucG15YmhoNHNu"
                      "&page[size]=20")}})
