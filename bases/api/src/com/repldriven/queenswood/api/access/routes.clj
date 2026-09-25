(ns com.repldriven.queenswood.api.access.routes
  (:require
    [com.repldriven.queenswood.api.access.handlers :as handlers]

    [com.repldriven.queenswood.api.shared.headers :as shared.headers]
    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.interceptors :as shared.interceptors]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.access-api.interface :refer
     [InvitationAlreadyExists InvitationAlreadyMember InvitationInvalidStatus
      InvitationNotFound MembershipAlreadyExists MembershipInvalidStatus
      MembershipLastOwner MembershipNotFound ReasonRequired RoleNotGranted]]
    [com.repldriven.queenswood.api-schema.interface :refer
     [ErrorExamples ErrorResponse]]
    [com.repldriven.queenswood.bank-api.interface :refer [BankUnnamed]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def ^:private optional-reason [:maybe [:ref "ReasonRequest"]])

;; `:maybe` lets the body be left out; the document says so with
;; `required: false` and names the schema, rather than a null branch.
(def ^:private optional-reason-request-body
  {:required false
   :content ^:replace
            {"application/json"
             {:schema {:$ref "#/components/schemas/ReasonRequest"}}}})

(defn- gate
  [level]
  [{"bearerAuth" [level]}])

(def ^:private my-memberships
  ["/me/memberships"
   {:openapi {:tags ["Me"] :security (gate "user")}}
   [""
    {:get {:summary "List the signed-in person's memberships"
           :openapi {:operationId "ListMyMemberships"
                     :description
                     (str "The caller's active memberships in every bank, "
                          "in the order they joined, a page at a time, each "
                          "naming its bank and role.")
                     :parameters ^:replace [shared.parameters/ref-page]}
           :parameters {:query shared.parameters/page-query}
           :responses {200 {:description
                            "A page of the caller's active memberships."
                            :body [:ref "MembershipList"]}}
           :handler handlers/list-my-memberships}}]
   ["/{membership-id}"
    {:parameters {:path {:membership-id [:ref "MembershipId"]}}}
    [""
     {:get {:summary "Retrieve one of the signed-in person's memberships"
            :openapi {:operationId "RetrieveMyMembership"
                      :description
                      (str "One of the caller's active memberships. Returns "
                           "404 for a membership that has ended or is not "
                           "the caller's.")}
            :responses {200 {:description "The membership."
                             :body [:ref "Membership"]}
                        404 (ErrorResponse [#'MembershipNotFound])}
            :handler handlers/get-my-membership}}]
    ["/leave"
     {:post {:summary "Leave a bank"
             :openapi {:operationId "LeaveMyMembership"
                       :description
                       (str "Ends the caller's own membership. Returns 404 "
                            "for a membership that is not the caller's. A "
                            "membership that has already ended is refused "
                            "with 409, as is the bank's last active owner "
                            "leaving.")}
             :responses {204 {:description "The membership was ended. No body."}
                         404 (ErrorResponse [#'MembershipNotFound])
                         409 (ErrorResponse [#'MembershipInvalidStatus
                                             #'MembershipLastOwner])}
             :handler handlers/leave-my-membership}}]]])

(def ^:private my-invitations
  ["/me/invitations"
   {:openapi {:tags ["Me"] :security (gate "user")}}
   [""
    {:get {:summary "List the signed-in person's pending invitations"
           :openapi {:operationId "ListMyInvitations"
                     :description
                     (str "Pending, unexpired invitations from any bank to "
                          "the email in the caller's token, newest first, a "
                          "page at a time. The list is empty when that "
                          "email is not verified.")
                     :parameters ^:replace [shared.parameters/ref-page]}
           :parameters {:query shared.parameters/page-query}
           :responses {200 {:description (str "A page of pending, unexpired "
                                              "invitations, none when the "
                                              "email is not verified.")
                            :body [:ref "RecipientInvitationList"]}}
           :handler handlers/list-my-invitations}}]
   ["/{invitation-id}"
    {:parameters {:path {:invitation-id [:ref "InvitationId"]}}}
    [""
     {:get {:summary "Retrieve an invitation as its recipient"
            :openapi {:operationId "RetrieveMyInvitation"
                      :description
                      (str
                       "The caller proves the invitation is theirs with the "
                       "`Invitation-Token` header from the emailed link, or "
                       "with a verified email that matches the invited address."
                       " Returns 404 when the caller cannot prove it.")
                      :parameters ^:replace
                                  [shared.parameters/ref-invitation-id
                                   shared.parameters/ref-invitation-token]}
            :responses {200 {:description "The invitation."
                             :body [:ref "RecipientInvitation"]}
                        404 (ErrorResponse [#'InvitationNotFound])}
            :handler handlers/get-my-invitation}}]
    ["/accept"
     {:post
      {:summary "Accept an invitation"
       :openapi {:operationId "AcceptMyInvitation"
                 :description
                 (str "Makes the caller a member of the invitation's "
                      "bank with the invited role, on the same proof "
                      "as retrieving it. An invitation that is not "
                      "pending or has expired is refused with 409, as "
                      "is a caller who is already a member of that " "bank.")
                 :parameters ^:replace
                             [shared.parameters/ref-invitation-id
                              shared.parameters/ref-invitation-token]}
       :responses {201 {:description "The membership created."
                        :body [:ref "Membership"]
                        :openapi {:headers {"Location" (shared.headers/location
                                                        "membership")}}}
                   404 (ErrorResponse [#'InvitationNotFound])
                   409 (ErrorResponse [#'InvitationInvalidStatus
                                       #'MembershipAlreadyExists])}
       :handler handlers/accept-my-invitation}}]
    ["/decline"
     {:post {:summary "Decline an invitation"
             :openapi {:operationId "DeclineMyInvitation"
                       :description
                       (str "Takes the same proof as retrieving the "
                            "invitation. An invitation that is not pending "
                            "or has expired is refused with 409.")
                       :parameters ^:replace
                                   [shared.parameters/ref-invitation-id
                                    shared.parameters/ref-invitation-token]}
             :responses {200 {:description "The declined invitation."
                              :body [:ref "RecipientInvitation"]}
                         404 (ErrorResponse [#'InvitationNotFound])
                         409 (ErrorResponse [#'InvitationInvalidStatus])}
             :handler handlers/decline-my-invitation}}]]])

(def ^:private memberships
  ["/memberships"
   {:openapi {:tags ["Memberships"]}}
   [""
    {:openapi {:security (gate "org:viewer")}
     :get {:summary "List the bank's memberships"
           :openapi {:operationId "ListMemberships"
                     :description
                     (str "The active memberships of the bank the `Bank-Id` "
                          "header names, in the order they joined, a page at "
                          "a time, each with the person's name and email, "
                          "their role, and who invited them. The founding "
                          "owner's membership is marked as having created "
                          "the organisation.")
                     :parameters ^:replace
                                 [shared.parameters/ref-page
                                  shared.parameters/ref-bank-id-header]}
           :parameters {:query shared.parameters/page-query}
           :responses {200 {:description
                            "A page of the bank's active memberships."
                            :body [:ref "MembershipList"]}}
           :handler handlers/list-memberships}}]
   ["/{membership-id}"
    {:parameters {:path {:membership-id [:ref "MembershipId"]}}}
    [""
     {:openapi {:security (gate "org:viewer")
                :parameters [shared.parameters/ref-bank-id-header]}
      :get {:summary "Retrieve a membership"
            :openapi
            {:operationId "RetrieveMembership"
             :description
             (str "An active membership of the bank the `Bank-Id` header "
                  "names, as the membership list shows it. Returns 404 for "
                  "a membership that has ended or belongs to another bank.")}
            :responses {200 {:description "The membership."
                             :body [:ref "Membership"]}
                        404 (ErrorResponse [#'MembershipNotFound])}
            :handler handlers/get-membership}}]
    ["/change-role"
     {:openapi {:security (gate "org:admin")
                :parameters [shared.parameters/ref-bank-id-header]}
      :post {:summary "Change a member's role"
             :openapi {:operationId "ChangeMembershipRole"
                       :description
                       (str "An owner may set any role, and an admin may "
                            "move a member only between admin, developer and "
                            "viewer, any other change being refused with "
                            "403. Demoting the bank's last active owner, or "
                            "changing an ended membership, is refused with "
                            "409. Setting the role already held changes and "
                            "records nothing.")
                       :requestBody {:required true}}
             :parameters {:body [:ref "ChangeRoleRequest"]}
             :responses {200 {:description "The membership with the new role."
                              :body [:ref "Membership"]}
                         403 (ErrorExamples [#'RoleNotGranted])
                         404 (ErrorResponse [#'MembershipNotFound])
                         409 (ErrorResponse [#'MembershipInvalidStatus
                                             #'MembershipLastOwner])}
             :handler handlers/change-role}}]
    ["/remove"
     {:openapi {:security (gate "org:admin")
                :parameters [shared.parameters/ref-bank-id-header]}
      :post {:summary "Remove a member from the bank"
             :openapi {:operationId "RemoveMembership"
                       :description
                       (str
                        "Ends the membership, recording the optional reason "
                        "in the bank's audit log. An admin removing an "
                        "owner is refused with 403. Removing the last active "
                        "owner, or an ended membership, is refused with 409.")
                       :requestBody optional-reason-request-body}
             :parameters {:body optional-reason}
             :responses {204 {:description "The membership was ended. No body."}
                         403 (ErrorExamples [#'RoleNotGranted])
                         404 (ErrorResponse [#'MembershipNotFound])
                         409 (ErrorResponse [#'MembershipInvalidStatus
                                             #'MembershipLastOwner])}
             :handler handlers/remove-membership}}]]])

(def ^:private invitations
  ["/invitations"
   {:openapi {:tags ["Invitations"]}}
   [""
    {:get {:summary "List the bank's invitations"
           :openapi {:operationId "ListInvitations"
                     :description
                     (str "Pending, expired and accepted invitations to the "
                          "bank the `Bank-Id` header names, an accepted one "
                          "with the address that "
                          "accepted it. Declined and withdrawn invitations "
                          "appear only in the audit log. Newest first, "
                          "a page at a time.")
                     :security (gate "org:viewer")
                     :parameters ^:replace
                                 [shared.parameters/ref-page
                                  shared.parameters/ref-bank-id-header]}
           :parameters {:query shared.parameters/page-query}
           :responses {200 {:description "A page of the bank's invitations."
                            :body [:ref "InvitationList"]}}
           :handler handlers/list-invitations}
     :post
     {:summary "Invite a person to the bank by email"
      :openapi {:operationId "CreateInvitation"
                :description
                (str "Creates a pending invitation, valid for seven days, and"
                     " emails its link to the address. An address that "
                     "already has a pending invitation or belongs to a member"
                     " is refused with 409. An admin inviting an owner is "
                     "refused with 403. An operator must give a reason, or "
                     "the request is refused with 422.")
                :security (gate "org:admin")
                :requestBody {:required true}
                :parameters ^:replace
                            [shared.parameters/ref-bank-id-header
                             shared.parameters/ref-idempotency-key]}
      :interceptors [server/require-idempotency-key
                     bank-idempotency/cache-response]
      :parameters {:body [:ref "CreateInvitationRequest"]}
      :responses (shared.idempotency/with-responses
                  {201 {:description (str "The invitation, whose link "
                                          "is emailed to the address.")
                        :body [:ref "Invitation"]
                        :openapi {:headers {"Location" (shared.headers/location
                                                        "invitation")}}}
                   403 (ErrorExamples [#'RoleNotGranted])
                   409 (ErrorResponse [#'InvitationAlreadyExists
                                       #'InvitationAlreadyMember])
                   422 (ErrorResponse [#'ReasonRequired])})
      :handler handlers/invite}}]
   ["/{invitation-id}"
    {:parameters {:path {:invitation-id [:ref "InvitationId"]}}}
    [""
     {:openapi {:security (gate "org:viewer")
                :parameters [shared.parameters/ref-bank-id-header]}
      :get {:summary "Retrieve an invitation"
            :openapi {:operationId "RetrieveInvitation"
                      :description
                      (str
                       "An invitation to the bank the `Bank-Id` header names, "
                       "in any status; an accepted invitation includes the "
                       "address that accepted it. Returns 404 for an invitation"
                       " to another bank.")}
            :responses {200 {:description "The invitation."
                             :body [:ref "Invitation"]}
                        404 (ErrorResponse [#'InvitationNotFound])}
            :handler handlers/get-invitation}}]
    ["/withdraw"
     {:openapi {:security (gate "org:admin")
                :parameters [shared.parameters/ref-bank-id-header]}
      :post {:summary "Withdraw a pending invitation"
             :openapi
             {:operationId "WithdrawInvitation"
              :description
              (str "The invitation can no longer be accepted. An invitation"
                   " that is not pending, or has expired, is refused with "
                   "409. An admin withdrawing an owner's invitation is "
                   "refused with 403.")
              :requestBody optional-reason-request-body}
             :parameters {:body optional-reason}
             :responses {200 {:description "The withdrawn invitation."
                              :body [:ref "Invitation"]}
                         403 (ErrorExamples [#'RoleNotGranted])
                         404 (ErrorResponse [#'InvitationNotFound])
                         409 (ErrorResponse [#'InvitationInvalidStatus])}
             :handler handlers/withdraw-invitation}}]
    ["/resend"
     {:openapi {:security (gate "org:admin")}
      :post {:summary "Resend an invitation"
             :openapi
             {:operationId "ResendInvitation"
              :description
              (str "Emails a fresh link for a pending or expired invitation"
                   " and sets its expiry seven days from now. Links in "
                   "earlier emails stop working. An invitation in any other"
                   " status is refused with 409. An admin resending an "
                   "owner's invitation is refused with 403.")
              :requestBody optional-reason-request-body
              :parameters ^:replace
                          [shared.parameters/ref-invitation-id
                           shared.parameters/ref-bank-id-header
                           shared.parameters/ref-idempotency-key]}
             :interceptors [server/require-idempotency-key
                            bank-idempotency/cache-response]
             :parameters {:body optional-reason}
             :responses (shared.idempotency/with-responses
                         {200 {:description (str "The invitation, with a "
                                                 "fresh expiry.")
                               :body [:ref "Invitation"]}
                          403 (ErrorExamples [#'RoleNotGranted])
                          404 (ErrorResponse [#'InvitationNotFound])
                          409 (ErrorResponse [#'InvitationInvalidStatus])})
             :handler handlers/resend-invitation}}]]])

;; The bank the `Bank-Id` header names: a member's own, or any an
;; operator names. `named-bank` refuses an operator who names none.
(def ^:private audit-events
  ["/bank/audit-events"
   {:openapi {:tags ["Audit"]
              :security [{"bearerAuth" ["org:viewer"]}
                         {"bearerAuth" ["admin"]}]}
    :interceptors [shared.interceptors/named-bank]
    :get {:summary "List the bank's audit events"
          :openapi {:operationId "ListAuditEvents"
                    :description
                    (str "The audit log of the bank the `Bank-Id` header "
                         "names, newest first: every change to who may act "
                         "for it — its creation, invitations, role changes, "
                         "removals and departures, and the operator's own "
                         "acts — each naming who made it and any reason "
                         "given.")
                    :parameters ^:replace
                                [shared.parameters/ref-page
                                 shared.parameters/ref-bank-id-header]}
          :parameters {:query shared.parameters/page-query}
          :responses {200 {:description "One page of the bank's audit events."
                           :body [:ref "AuditEventList"]}
                      403 (ErrorExamples [#'BankUnnamed])}
          :handler handlers/list-audit-events}}])

(def routes
  [my-memberships my-invitations memberships invitations audit-events])
