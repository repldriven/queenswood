(ns com.repldriven.queenswood.access-api.components
  (:require
    [com.repldriven.queenswood.access-api.coercion :as coercion]
    [com.repldriven.queenswood.access-api.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry list-schema]]
    [com.repldriven.queenswood.me-api.interface :as me-api]

    [clojure.string :as str]))

(def InvitationId (schema/id-schema "InvitationId" "inv" examples/InvitationId))

(def AuditEventId (schema/id-schema "AuditEventId" "aev" examples/AuditEventId))

(def InvitationStatus
  (coercion/invitation-status-enum-schema {:json-schema/example "pending"}))

(def AuditEventKind
  (coercion/audit-event-kind-enum-schema {:json-schema/example
                                          "member-role-changed"}))

(def AuditSubjectKind
  (coercion/audit-subject-kind-enum-schema {:json-schema/example "member"}))

(def EmailAddress
  [:re
   {:title "EmailAddress" :json-schema/example "c.babbage@example.com"}
   #"^[^\s@]{1,64}@[^\s@]{1,255}$"])

(def Member
  [:map
   {:json-schema/example me-api/Member
    :description
    "A person's role in a bank, the same record whether the bank or the
    person reads it. A member that created the organisation joined by
    no invitation; any other names the invitation, who sent it and the
    address it was sent to."}
   [:member-id [:ref "MemberId"]]
   [:bank-id [:ref "BankId"]]
   [:bank-name {:optional true} [:ref "Name"]]
   [:user-id [:ref "UserId"]]
   [:name {:optional true} string?]
   [:email {:optional true} string?]
   [:role [:ref "Role"]]
   [:created-organisation boolean?]
   [:invitation-id {:optional true} [:ref "InvitationId"]]
   [:invited-by {:optional true} [:ref "Actor"]]
   [:invited-email {:optional true} string?]
   [:created-at [:ref "Timestamp"]]
   [:created-by [:ref "Actor"]]
   [:updated-at {:optional true} [:ref "Timestamp"]]])

(def MemberList (list-schema "Member" examples/MemberList))

(def Invitation
  [:map
   {:json-schema/example examples/Invitation
    :description "An invitation to the bank, as the bank's members see it."}
   [:invitation-id [:ref "InvitationId"]]
   [:bank-id [:ref "BankId"]]
   [:email string?]
   [:role [:ref "Role"]]
   [:status [:ref "InvitationStatus"]]
   [:expires-at [:ref "Timestamp"]]
   [:reason {:optional true} string?]
   [:created-at [:ref "Timestamp"]]
   [:created-by [:ref "Actor"]]
   [:updated-at {:optional true} [:ref "Timestamp"]]])

(def InvitationList (list-schema "Invitation" examples/InvitationList))

(def RecipientInvitation
  [:map
   {:json-schema/example examples/RecipientInvitation
    :description "An invitation as the person it was sent to sees it."}
   [:invitation-id [:ref "InvitationId"]]
   [:bank-id [:ref "BankId"]]
   [:bank-name {:optional true} [:ref "Name"]]
   [:email string?]
   [:role [:ref "Role"]]
   [:status [:ref "InvitationStatus"]]
   [:expires-at [:ref "Timestamp"]]
   [:created-at [:ref "Timestamp"]]
   [:created-by [:ref "Actor"]]])

(def RecipientInvitationList
  (list-schema "RecipientInvitation" examples/RecipientInvitationList))

(def CreateInvitationRequest
  [:map {:closed true :json-schema/example examples/CreateInvitationRequest}
   [:email [:ref "EmailAddress"]]
   [:role [:ref "Role"]]
   [:reason {:optional true} [:string {:min 1 :max 500}]]])

(def ChangeRoleRequest
  [:map {:closed true :json-schema/example examples/ChangeRoleRequest}
   [:role [:ref "Role"]]
   [:reason {:optional true} [:string {:min 1 :max 500}]]])

(def ReasonRequest
  [:map {:closed true :json-schema/example examples/ReasonRequest}
   [:reason {:optional true} [:string {:min 1 :max 500}]]])

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
                        #'AuditEventList #'AuditSubjectKind #'ChangeRoleRequest
                        #'CreateInvitationRequest #'EmailAddress #'Invitation
                        #'InvitationId #'InvitationStatus #'InvitationList
                        #'Member #'MemberList #'ReasonRequest
                        #'RecipientInvitation #'RecipientInvitationList]))
