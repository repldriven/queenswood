(ns com.repldriven.queenswood.api.access.components
  (:require
    [com.repldriven.queenswood.api.access.coercion :as coercion]
    [com.repldriven.queenswood.api.access.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry]]))

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

(def InvitationToken
  [:re
   {:title "InvitationToken"
    :json-schema/example examples/InvitationToken
    :description
    "The token an invitation's link carries. Returned when the invitation
    is created and each time it is resent, and by nothing else; the bank
    keeps only its hash."}
   #"^[A-Za-z0-9_-]{43}$"])

(def EmailAddress
  [:re
   {:title "EmailAddress" :json-schema/example "c.babbage@example.com"}
   #"^[^\s@]{1,64}@[^\s@]{1,255}$"])

(def Actor
  [:map
   {:json-schema/example examples/Actor
    :description
    "Who made a change: a member by user id, or an operator by user id or
    client id."}
   [:kind [:ref "ActorKind"]]
   [:principal-id string?]])

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

(def Members
  [:map {:json-schema/example examples/Members}
   [:items [:vector [:ref "Member"]]]])

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

(def InvitationWithToken
  [:map {:closed true :json-schema/example examples/InvitationWithToken}
   [:invitation [:ref "Invitation"]]
   [:token [:ref "InvitationToken"]]])

(def Invitations
  [:map {:json-schema/example examples/Invitations}
   [:items [:vector [:ref "Invitation"]]]])

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

(def RecipientInvitations
  [:map {:json-schema/example examples/RecipientInvitations}
   [:items [:vector [:ref "RecipientInvitation"]]]])

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
    role change `role-after`."}
   [:access-event-id [:ref "AccessEventId"]]
   [:bank-id [:ref "BankId"]]
   [:kind [:ref "AccessEventKind"]]
   [:actor [:ref "Actor"]]
   [:subject-user-id {:optional true} [:ref "UserId"]]
   [:membership-id {:optional true} [:ref "MembershipId"]]
   [:invitation-id {:optional true} [:ref "InvitationId"]]
   [:email {:optional true} string?]
   [:role-before {:optional true} [:ref "Role"]]
   [:role-after {:optional true} [:ref "Role"]]
   [:reason {:optional true} string?]
   [:occurred-at [:ref "Timestamp"]]])

(def AccessEventLinks
  [:map
   [:next {:optional true} string?]
   [:prev {:optional true} string?]])

(def AccessEvents
  [:map {:json-schema/example examples/AccessEvents}
   [:items [:vector [:ref "AccessEvent"]]]
   [:links {:optional true} [:ref "AccessEventLinks"]]])

(def registry
  (components-registry
   [#'AccessEvent #'AccessEventId #'AccessEventKind #'AccessEventLinks
    #'AccessEvents #'Actor #'ActorKind #'ChangeRoleRequest
    #'CreateInvitationRequest #'EmailAddress #'Invitation #'InvitationId
    #'InvitationStatus #'InvitationToken #'InvitationWithToken #'Invitations
    #'Member #'Members #'ReasonRequest #'RecipientInvitation
    #'RecipientInvitations]))
