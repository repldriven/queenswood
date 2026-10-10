(ns com.repldriven.queenswood.audit-api.components
  (:require
    [com.repldriven.queenswood.audit-api.coercion :as coercion]
    [com.repldriven.queenswood.audit-api.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry list-schema]]

    [clojure.string :as str]))

(def AuditEventId (schema/id-schema "AuditEventId" "aev" examples/AuditEventId))

(def AuditEventKind
  (coercion/audit-event-kind-enum-schema {:json-schema/example
                                          "member-role-changed"}))

(def AuditSubjectKind
  (coercion/audit-subject-kind-enum-schema {:json-schema/example "member"}))

(def AuditEvent
  [:map
   {:json-schema/example examples/AuditEvent
    :description
    "One act in the bank's audit log: what was done, to which record,
    who did it, the request that asked for it and any reason given. An
    invitation's events name the invitation, its address and its role
    as `role-after`; a member's name the member and `role-before`, and a
    role change `role-after`. `subject-name` names the person a member
    act concerns from their user record, and is absent when they have
    none."}
   [:audit-event-id [:ref "AuditEventId"]]
   [:bank-id [:ref "BankId"]]
   [:kind [:ref "AuditEventKind"]]
   [:subject-kind [:ref "AuditSubjectKind"]]
   [:subject-id string?]
   [:actor [:ref "Actor"]]
   [:request-id {:optional true} string?]
   [:subject-user-id {:optional true} [:ref "UserId"]]
   [:subject-name {:optional true} string?]
   [:member-id {:optional true} [:ref "MemberId"]]
   [:invitation-id {:optional true} [:ref "InvitationId"]]
   [:email {:optional true} string?]
   [:role-before {:optional true} [:ref "Role"]]
   [:role-after {:optional true} [:ref "Role"]]
   [:reason {:optional true} string?]
   [:occurred-at [:ref "Timestamp"]]])

(def AuditEventList (list-schema "AuditEvent" examples/AuditEventList))

(def AuditEventQuery
  "The audit log's query: a page, and optionally the kinds of act to
  list, comma-separated."
  [:map {:closed true}
   [:page {:optional true} [:ref "PageQuery"]]
   [:kind {:optional true}
    [:vector
     {:decode/api (fn [v] (if (string? v) (str/split v #",") v))}
     [:ref "AuditEventKind"]]]])

(def registry
  (components-registry [#'AuditEvent #'AuditEventId #'AuditEventKind
                        #'AuditEventList #'AuditSubjectKind]))
