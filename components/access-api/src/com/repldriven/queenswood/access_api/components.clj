(ns com.repldriven.queenswood.access-api.components
  (:require
    [com.repldriven.queenswood.access-api.coercion :as coercion]
    [com.repldriven.queenswood.access-api.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry list-schema]]
    [com.repldriven.queenswood.me-api.interface :as me-api]))

(def InvitationId (schema/id-schema "InvitationId" "inv" examples/InvitationId))

(def InvitationStatus
  (coercion/invitation-status-enum-schema {:json-schema/example "pending"}))

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
   [:updated-at {:optional true} [:ref "Timestamp"]]
   [:updated-by {:optional true} [:ref "Actor"]]])

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
   [:updated-at {:optional true} [:ref "Timestamp"]]
   [:updated-by {:optional true} [:ref "Actor"]]])

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

(def registry
  (components-registry
   [#'ChangeRoleRequest #'CreateInvitationRequest #'EmailAddress #'Invitation
    #'InvitationId #'InvitationStatus #'InvitationList #'Member #'MemberList
    #'ReasonRequest #'RecipientInvitation #'RecipientInvitationList]))
