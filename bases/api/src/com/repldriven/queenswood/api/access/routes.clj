(ns com.repldriven.queenswood.api.access.routes
  (:require
    [com.repldriven.queenswood.api.access.examples :refer
     [InvitationAlreadyExists InvitationAlreadyMember InvitationInvalidStatus
      InvitationNotFound MembershipAlreadyExists MembershipInvalidStatus
      MembershipLastOwner MembershipNotFound ReasonRequired RoleNotGranted]]
    [com.repldriven.queenswood.api.access.handlers :as handlers]

    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer [ErrorResponse]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def ^:private list-access-events-query-schema
  [:map {:closed true} [:page {:optional true} [:ref "PageQuery"]]])

(def ^:private optional-reason [:maybe [:ref "ReasonRequest"]])

(defn- gate
  [level]
  [{"bearerAuth" [level]}])

(def routes
  [["/me/invitations"
    {:openapi {:tags ["Access"] :security (gate "user")}}
    [""
     {:get {:summary (str "List the pending invitations to the signed-in "
                          "person's verified email")
            :openapi {:operationId "ListMyInvitations"}
            :responses {200 {:description (str "Pending, unexpired "
                                               "invitations, none when the "
                                               "email is not verified.")
                             :body [:ref "RecipientInvitations"]}}
            :handler handlers/list-my-invitations}}]
    ["/{invitation-id}"
     {:parameters {:path {:invitation-id [:ref "InvitationId"]}}}
     [""
      {:get {:summary "Retrieve an invitation as its recipient"
             :openapi {:operationId "RetrieveMyInvitation"
                       :parameters ^:replace
                                   [shared.parameters/ref-invitation-id
                                    shared.parameters/ref-invitation-token]}
             :responses {200 {:description "The invitation."
                              :body [:ref "RecipientInvitation"]}
                         404 (ErrorResponse [#'InvitationNotFound])}
             :handler handlers/get-my-invitation}}]
     ["/accept"
      {:post {:summary "Accept an invitation, becoming a member of its bank"
              :openapi {:operationId "AcceptInvitation"
                        :parameters ^:replace
                                    [shared.parameters/ref-invitation-id
                                     shared.parameters/ref-invitation-token]}
              :responses {201 {:description "The membership created."
                               :body [:ref "Membership"]}
                          404 (ErrorResponse [#'InvitationNotFound])
                          409 (ErrorResponse [#'InvitationInvalidStatus
                                              #'MembershipAlreadyExists])}
              :handler handlers/accept-invitation}}]
     ["/decline"
      {:post {:summary "Decline an invitation"
              :openapi {:operationId "DeclineInvitation"
                        :parameters ^:replace
                                    [shared.parameters/ref-invitation-id
                                     shared.parameters/ref-invitation-token]}
              :responses {200 {:description "The declined invitation."
                               :body [:ref "RecipientInvitation"]}
                          404 (ErrorResponse [#'InvitationNotFound])
                          409 (ErrorResponse [#'InvitationInvalidStatus])}
              :handler handlers/decline-invitation}}]]]
   ["/me/memberships/{membership-id}/leave"
    {:openapi {:tags ["Access"] :security (gate "user")}
     :parameters {:path {:membership-id [:ref "MembershipId"]}}
     :post {:summary "Leave a bank, ending the caller's own membership"
            :openapi {:operationId "LeaveMembership"}
            :responses {204 {:description "The membership was ended. No body."}
                        404 (ErrorResponse [#'MembershipNotFound])
                        409 (ErrorResponse [#'MembershipInvalidStatus
                                            #'MembershipLastOwner])}
            :handler handlers/leave}}]
   ["/members"
    {:openapi {:tags ["Access"]}}
    [""
     {:openapi {:security (gate "org:viewer")
                :parameters [shared.parameters/ref-bank-id-header]}
      :get {:summary "List the bank's active members"
            :openapi {:operationId "ListMembers"}
            :responses {200 {:description "The bank's active members."
                             :body [:ref "Members"]}}
            :handler handlers/list-members}}]
    ["/{membership-id}"
     {:parameters {:path {:membership-id [:ref "MembershipId"]}}}
     ["/change-role"
      {:openapi {:security (gate "org:admin")
                 :parameters [shared.parameters/ref-bank-id-header]}
       :post {:summary "Change a member's role"
              :openapi {:operationId "ChangeMemberRole"
                        :requestBody {:required true}}
              :parameters {:body [:ref "ChangeRoleRequest"]}
              :responses {200 {:description "The member with the new role."
                               :body [:ref "Member"]}
                          403 (ErrorResponse [#'RoleNotGranted])
                          404 (ErrorResponse [#'MembershipNotFound])
                          409 (ErrorResponse [#'MembershipInvalidStatus
                                              #'MembershipLastOwner])}
              :handler handlers/change-role}}]
     ["/remove"
      {:openapi {:security (gate "org:admin")
                 :parameters [shared.parameters/ref-bank-id-header]}
       :post
       {:summary "Remove a member from the bank"
        :openapi {:operationId "RemoveMember" :requestBody {:required false}}
        :parameters {:body optional-reason}
        :responses {204 {:description "The membership was ended. No body."}
                    403 (ErrorResponse [#'RoleNotGranted])
                    404 (ErrorResponse [#'MembershipNotFound])
                    409 (ErrorResponse [#'MembershipInvalidStatus
                                        #'MembershipLastOwner])}
        :handler handlers/remove-member}}]]]
   ["/invitations"
    {:openapi {:tags ["Access"]}}
    [""
     {:get {:summary (str "List the bank's pending, expired and accepted "
                          "invitations")
            :openapi {:operationId "ListInvitations"
                      :security (gate "org:viewer")
                      :parameters [shared.parameters/ref-bank-id-header]}
            :responses {200 {:description "The bank's invitations."
                             :body [:ref "Invitations"]}}
            :handler handlers/list-invitations}
      :post {:summary (str "Invite a person to the bank, answering the "
                           "invitation and its token")
             :openapi {:operationId "CreateInvitation"
                       :security (gate "org:admin")
                       :requestBody {:required true}
                       :parameters ^:replace
                                   [shared.parameters/ref-bank-id-header
                                    shared.parameters/ref-idempotency-key]}
             :interceptors [server/require-idempotency-key
                            bank-idempotency/cache-response]
             :parameters {:body [:ref "CreateInvitationRequest"]}
             :responses (shared.idempotency/with-responses
                         {201 {:description (str "The invitation and the "
                                                 "token its link carries.")
                               :body [:ref "InvitationWithToken"]}
                          403 (ErrorResponse [#'RoleNotGranted])
                          409 (ErrorResponse [#'InvitationAlreadyExists
                                              #'InvitationAlreadyMember])
                          422 (ErrorResponse [#'ReasonRequired])})
             :handler handlers/invite}}]
    ["/{invitation-id}"
     {:parameters {:path {:invitation-id [:ref "InvitationId"]}}}
     ["/withdraw"
      {:openapi {:security (gate "org:admin")
                 :parameters [shared.parameters/ref-bank-id-header]}
       :post {:summary "Withdraw a pending invitation"
              :openapi {:operationId "WithdrawInvitation"
                        :requestBody {:required false}}
              :parameters {:body optional-reason}
              :responses {200 {:description "The withdrawn invitation."
                               :body [:ref "Invitation"]}
                          403 (ErrorResponse [#'RoleNotGranted])
                          404 (ErrorResponse [#'InvitationNotFound])
                          409 (ErrorResponse [#'InvitationInvalidStatus])}
              :handler handlers/withdraw-invitation}}]
     ["/resend"
      {:openapi {:security (gate "org:admin")}
       :post {:summary (str "Resend a pending or expired invitation under a "
                            "fresh token")
              :openapi {:operationId "ResendInvitation"
                        :requestBody {:required false}
                        :parameters ^:replace
                                    [shared.parameters/ref-invitation-id
                                     shared.parameters/ref-bank-id-header
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :parameters {:body optional-reason}
              :responses (shared.idempotency/with-responses
                          {200 {:description (str "The invitation and the "
                                                  "fresh token its link "
                                                  "carries.")
                                :body [:ref "InvitationWithToken"]}
                           403 (ErrorResponse [#'RoleNotGranted])
                           404 (ErrorResponse [#'InvitationNotFound])
                           409 (ErrorResponse [#'InvitationInvalidStatus])})
              :handler handlers/resend-invitation}}]]]
   ["/access-events"
    {:openapi {:tags ["Access"] :security (gate "org:viewer")}
     :get {:summary "Page the bank's access history, newest first"
           :openapi {:operationId "ListAccessEvents"
                     :parameters ^:replace
                                 [shared.parameters/ref-page
                                  shared.parameters/ref-bank-id-header]}
           :parameters {:query list-access-events-query-schema}
           :responses {200 {:description "One page of the bank's access events."
                            :body [:ref "AccessEvents"]}}
           :handler handlers/list-access-events}}]])
