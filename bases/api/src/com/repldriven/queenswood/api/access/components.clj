(ns com.repldriven.queenswood.api.access.components
  (:require
    [com.repldriven.queenswood.api.access.coercion :as coercion]
    [com.repldriven.queenswood.api.access.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry list-schema]]))

(def InvitationId (schema/id-schema "InvitationId" "inv" examples/InvitationId))

(def AccessEventId
  (schema/id-schema "AccessEventId" "aev" examples/AccessEventId))

(def InvitationStatus
  (coercion/invitation-status-enum-schema {:json-schema/example "pending"}))

(def ActorKind
  (coercion/actor-kind-enum-schema {:json-schema/example "member"}))

(def AccessEventKind
  (coercion/access-event-kind-enum-schema {:json-schema/example
                                           "role-changed"}))

(def EmailAddress
  [:re
   {:title "EmailAddress" :json-schema/example "c.babbage@example.com"}
   #"^[^\s@]{1,64}@[^\s@]{1,255}$"])

(def Actor
  [:map
   {:json-schema/example examples/Actor
    :description
    "Who made a change: a member by user id, or an operator by user id or
    client id. A member, or an operator signed in as a user, is named from
    their user record whether or not still a member, by their email when
    the record has no name; the platform's own client is named
    `Queenswood`. To the person an invitation was sent to, an inviter with
    no name on record is named by the organisation."}
   [:kind [:ref "ActorKind"]]
   [:principal-id string?]
   [:name string?]])

(def Member
  [:map
   {:json-schema/example examples/Member
    :description
    "An active member of the bank. A member who created the organisation
    joined by no invitation; any other names the invitation, who sent it
    and the address it was sent to."}
   [:membership-id [:ref "MembershipId"]]
   [:user-id [:ref "UserId"]]
   [:name {:optional true} string?]
   [:email {:optional true} string?]
   [:role [:ref "Role"]]
   [:joined-at [:ref "Timestamp"]]
   [:created-organisation boolean?]
   [:invitation-id {:optional true} [:ref "InvitationId"]]
   [:invited-by {:optional true} [:ref "Actor"]]
   [:invited-email {:optional true} string?]])

(def MemberList (list-schema "Member" examples/MemberList))

(def Invitation
  [:map
   {:json-schema/example examples/Invitation
    :description
    "An invitation to the bank, as the bank's members see it. Once
    accepted it names the accepting user and the address they signed in
    with."}
   [:invitation-id [:ref "InvitationId"]]
   [:bank-id [:ref "BankId"]]
   [:email string?]
   [:role [:ref "Role"]]
   [:status [:ref "InvitationStatus"]]
   [:expires-at [:ref "Timestamp"]]
   [:invited-by [:ref "Actor"]]
   [:reason {:optional true} string?]
   [:accepted-by-user-id {:optional true} [:ref "UserId"]]
   [:accepted-email {:optional true} string?]
   [:created-at [:ref "Timestamp"]]
   [:updated-at [:ref "Timestamp"]]])

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
   [:invited-by [:ref "Actor"]]
   [:created-at [:ref "Timestamp"]]])

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

(def AccessEvent
  [:map
   {:json-schema/example examples/AccessEvent
    :description
    "One change to who may act for the bank. An invitation's events name
    the invitation, its address and its role as `role-after`; a
    membership's name the member, the membership and `role-before`, and a
    role change `role-after`. `subject-name` names the member from their
    user record, and is absent when they have none."}
   [:access-event-id [:ref "AccessEventId"]]
   [:bank-id [:ref "BankId"]]
   [:kind [:ref "AccessEventKind"]]
   [:actor [:ref "Actor"]]
   [:subject-user-id {:optional true} [:ref "UserId"]]
   [:subject-name {:optional true} string?]
   [:membership-id {:optional true} [:ref "MembershipId"]]
   [:invitation-id {:optional true} [:ref "InvitationId"]]
   [:email {:optional true} string?]
   [:role-before {:optional true} [:ref "Role"]]
   [:role-after {:optional true} [:ref "Role"]]
   [:reason {:optional true} string?]
   [:occurred-at [:ref "Timestamp"]]])

(def AccessEventList (list-schema "AccessEvent" examples/AccessEventList))

(def registry
  (components-registry
   [#'AccessEvent #'AccessEventId #'AccessEventKind #'AccessEventList #'Actor
    #'ActorKind #'ChangeRoleRequest #'CreateInvitationRequest #'EmailAddress
    #'Invitation #'InvitationId #'InvitationStatus #'InvitationList #'Member
    #'MemberList #'ReasonRequest #'RecipientInvitation
    #'RecipientInvitationList]))
