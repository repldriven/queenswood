(ns com.repldriven.queenswood.api.access.examples
  (:require
    [com.repldriven.queenswood.api.me.examples :as me-examples]

    [com.repldriven.queenswood.api-schema.interface :refer
     [examples-registry]]))

(def InvitationId "inv.01kprbmgcj35ptc8npmybhh4sm")

(def AccessEventId "aev.01kprbmgcj35ptc8npmybhh4sn")

(def InvitationToken "mZzGQMQVkb1hnhQq6hXAZEOHjWHnjB8aHdRJAJw3hMw")

(def invitee-user-id "usr.01kprbmgcj35ptc8npmybhh4sp")

(def invitee-membership-id "mem.01kprbpdwa9q5n2t7vwsx84a3n")

(def Actor {:kind :member :principal-id me-examples/UserId})

(def Member
  {:membership-id me-examples/MembershipId
   :user-id me-examples/UserId
   :name "Ada Lovelace"
   :email "ada@example.com"
   :role :owner
   :joined-at "2026-05-18T09:15:00Z"
   :created-organisation true})

(def invited-member
  {:membership-id invitee-membership-id
   :user-id invitee-user-id
   :name "Charles Babbage"
   :email "charles@example.com"
   :role :developer
   :joined-at "2026-05-19T10:00:00Z"
   :created-organisation false
   :invitation-id InvitationId
   :invited-by Actor
   :invited-email "c.babbage@example.com"})

(def Members {:items [Member invited-member]})

(def Invitation
  {:invitation-id InvitationId
   :bank-id me-examples/BankId
   :email "c.babbage@example.com"
   :role :developer
   :status :pending
   :expires-at "2026-05-25T10:00:00Z"
   :invited-by Actor
   :reason "Joining the payments team"
   :created-at "2026-05-18T10:00:00Z"
   :updated-at "2026-05-18T10:00:00Z"})

(def InvitationWithToken {:invitation Invitation :token InvitationToken})

(def accepted-invitation
  (assoc Invitation
         :status :accepted
         :accepted-by-user-id invitee-user-id
         :accepted-email "charles@example.com"
         :updated-at "2026-05-19T10:00:00Z"))

(def Invitations {:items [Invitation accepted-invitation]})

(def RecipientInvitation
  {:invitation-id InvitationId
   :bank-id me-examples/BankId
   :bank-name "Ada's Bank"
   :email "c.babbage@example.com"
   :role :developer
   :status :pending
   :expires-at "2026-05-25T10:00:00Z"
   :invited-by Actor
   :created-at "2026-05-18T10:00:00Z"})

(def RecipientInvitations {:items [RecipientInvitation]})

(def CreateInvitationRequest
  {:email "c.babbage@example.com"
   :role :developer
   :reason "Joining the payments team"})

(def ChangeRoleRequest {:role :admin :reason "Leads the payments team"})

(def ReasonRequest {:reason "Left the company"})

(def AccessEvent
  {:access-event-id AccessEventId
   :bank-id me-examples/BankId
   :kind :role-changed
   :actor Actor
   :subject-user-id invitee-user-id
   :membership-id invitee-membership-id
   :role-before :developer
   :role-after :admin
   :reason "Leads the payments team"
   :occurred-at "2026-06-01T09:00:00Z"})

(def AccessEvents
  {:items [AccessEvent]
   :links {:next (str "/v1/access-events?page[after]="
                      "djE6YWV2LjAxa3ByYm1nY2ozNXB0YzhucG15YmhoNHNu"
                      "&page[size]=20")}})

(def InvitationNotFound
  {:value {:title "REJECTED"
           :type ":invitation/not-found"
           :status 404
           :detail "Invitation not found"}})

(def MembershipNotFound
  {:value {:title "REJECTED"
           :type ":membership/not-found"
           :status 404
           :detail "Membership not found"}})

(def RoleNotGranted
  {:value {:title "UNAUTHORIZED"
           :type ":membership/role-not-granted"
           :status 403
           :detail "Your role does not allow this"}})

(def InvitationAlreadyExists
  {:value {:title "REJECTED"
           :type ":invitation/already-exists"
           :status 409
           :detail "That address has a pending invitation"}})

(def MembershipAlreadyExists
  {:value {:title "REJECTED"
           :type ":membership/already-exists"
           :status 409
           :detail "Already a member of this bank"}})

(def ReasonRequired
  {:value {:title "REJECTED"
           :type ":invitation/reason-required"
           :status 422
           :detail "An operator's invitation needs a reason"}})

(def registry
  (examples-registry [#'InvitationNotFound #'MembershipNotFound #'RoleNotGranted
                      #'InvitationAlreadyExists #'MembershipAlreadyExists
                      #'ReasonRequired]))
