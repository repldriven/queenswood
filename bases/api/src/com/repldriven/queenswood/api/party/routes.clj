(ns com.repldriven.queenswood.api.party.routes
  (:require
    [com.repldriven.queenswood.api.party.commands :as commands]
    [com.repldriven.queenswood.api.party.queries :as queries]

    [com.repldriven.queenswood.api.shared.headers :as shared.headers]
    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :as api-schema :refer
     [ErrorExamples ErrorResponse]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]
    [com.repldriven.queenswood.party-api.interface :as party-api :refer
     [ExternalReferenceTaken MissingEmail PartyInvalidStatus PartyMergeIntoSelf
      PartyNotFound PartyOpenAccounts UnsupportedChannel
      VerificationInvalidStatus VerificationNotFound
      VerificationSessionNotFound]]

    [com.repldriven.mono.server.interface :as server]))

(def ^:private get-party-query-schema
  [:map {:closed true} [:embed {:optional true} [:ref "PartyEmbedQuery"]]])

(def routes
  [["/parties" {:openapi {:tags ["Parties"]}}
    [""
     {:get {:summary "List parties"
            :openapi {:operationId "ListParties"
                      :description
                      (str "The parties of the bank the `Bank-Id` header "
                           "names, a page at a time.")
                      :security [{"bearerAuth" ["org:viewer"]}]
                      :parameters ^:replace
                                  [shared.parameters/ref-page
                                   shared.parameters/ref-bank-id-header]}
            :parameters {:query shared.parameters/page-query}
            :responses {200 {:description "One page of the bank's parties."
                             :body [:ref "PartyList"]}}
            :handler queries/list-parties}
      :post {:summary "Create a party"
             :openapi {:operationId "CreateParty"
                       :description
                       (str "Only a person party can be created. It starts "
                            "pending until its identity is verified: open a "
                            "verification session to hand the person to the "
                            "identity provider. It becomes active if "
                            "verification accepts it or rejected if not, with "
                            "a `party.opened` or `party.rejected` webhook "
                            "notification. The person is registered by name "
                            "alone: their date of birth, address and "
                            "documents they give to the identity provider. "
                            "An `external-reference` another party of the "
                            "bank already has is refused with 409.")
                       :security [{"bearerAuth" ["org:developer"]}]
                       :requestBody {:required true}
                       :parameters ^:replace
                                   [shared.parameters/ref-bank-id-header
                                    shared.parameters/ref-idempotency-key]}
             :interceptors [server/require-idempotency-key
                            bank-idempotency/cache-response]
             :parameters {:body [:ref "CreatePartyRequest"]}
             :responses
             (shared.idempotency/with-responses
              {201 {:description "The created party, pending verification."
                    :body [:ref "CreatePartyResponse"]
                    :openapi {:headers {"Location" (shared.headers/location
                                                    "party")}
                              :links party-api/from-party}}
               403 (ErrorExamples [#'api-schema/PolicyDenied])
               409 (ErrorResponse [#'ExternalReferenceTaken])})
             :handler commands/create-party}}]
    ["/{party-id}" {:parameters {:path {:party-id [:ref "PartyId"]}}}
     [""
      {:openapi {:security [{"bearerAuth" ["org:viewer"]}]}
       :get {:summary "Retrieve a party"
             :openapi {:operationId "RetrieveParty"
                       :description
                       (str "Set `embed[person-identification]` to include "
                            "the person's names with the party. A merged "
                            "party names the party it was merged into.")
                       :parameters ^:replace
                                   [shared.parameters/ref-party-id
                                    shared.parameters/ref-party-embed
                                    shared.parameters/ref-bank-id-header]}
             :parameters {:query get-party-query-schema}
             :responses
             {200 {:description
                   "The party, with the names where the request embeds them."
                   :body [:ref "PartyDetail"]}
              404 (ErrorResponse [#'PartyNotFound])}
             :handler queries/get-party}}]
     ["/verification"
      {:openapi {:security [{"bearerAuth" ["org:viewer"]}]}
       :get {:summary "Retrieve the party's verification"
             :openapi {:operationId "RetrieveVerification"
                       :description
                       (str "The status of the person's identity "
                            "verification, and each verification and "
                            "screening the bank's policies ask of them as "
                            "established, in review, failed or outstanding, "
                            "an outstanding one with the reason it is "
                            "required. It never carries what the person's "
                            "document says. A party with no verification "
                            "returns 404.")
                       :parameters ^:replace
                                   [shared.parameters/ref-party-id
                                    shared.parameters/ref-bank-id-header]}
             :responses {200 {:description "The party's verification."
                              :body [:ref "Verification"]}
                         404 (ErrorResponse [#'VerificationNotFound])}
             :handler queries/get-verification}}]
     ["/verification-sessions"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post {:summary "Open a verification session"
              :openapi {:operationId "OpenVerificationSession"
                        :description
                        (str
                         "Starts, or resumes, the person's run with the "
                         "identity provider, returning the session opening. "
                         "Once it is ready, retrieving it returns a hand-off "
                         "URL to open in a browser or a mobile WebView, which "
                         "returns the person to `return-url`, and a "
                         "`party.verification-session-ready` webhook "
                         "notification follows. Where the provider refuses "
                         "the person's run, the session becomes `failed` with "
                         "a `failure-reason`, a "
                         "`party.verification-session-failed` webhook "
                         "notification follows, and another session may be "
                         "opened. A verification that is no "
                         "longer pending is refused with 409, and a channel "
                         "the provider does not offer, or a missing `email` "
                         "the provider needs, with 422.")
                        :requestBody {:required true}
                        :parameters ^:replace
                                    [shared.parameters/ref-party-id
                                     shared.parameters/ref-bank-id-header
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :parameters {:body [:ref "OpenVerificationSessionRequest"]}
              :responses
              (shared.idempotency/with-responses
               {202 {:description "The session, opening."
                     :body [:ref "VerificationSession"]
                     :openapi {:headers {"Location" (shared.headers/location
                                                     "verification session")}}}
                403 (ErrorExamples [#'api-schema/PolicyDenied])
                404 (ErrorResponse [#'VerificationNotFound])
                409 (ErrorResponse [#'VerificationInvalidStatus])
                422 (ErrorResponse [#'UnsupportedChannel #'MissingEmail])
                429 (ErrorResponse [#'api-schema/PolicyLimitExceeded])})
              :handler commands/open-verification-session}}]
     ["/verification-sessions/{session-id}"
      {:openapi {:security [{"bearerAuth" ["org:viewer"]}]}
       :parameters {:path {:session-id [:ref "VerificationSessionId"]}}
       :get {:summary "Retrieve a verification session"
             :openapi {:operationId "RetrieveVerificationSession"
                       :description
                       (str "The session, `opening` until the provider "
                            "returns a hand-off, `ready` with it, `expired` "
                            "once the hand-off lapses, and `completed` once "
                            "the verification decides. Only a ready session "
                            "carries its hand-off.")
                       :parameters
                       ^:replace
                       [shared.parameters/ref-party-id
                        shared.parameters/ref-verification-session-id
                        shared.parameters/ref-bank-id-header]}
             :responses {200 {:description "The verification session."
                              :body [:ref "VerificationSession"]}
                         404 (ErrorResponse [#'VerificationSessionNotFound])}
             :handler queries/get-verification-session}}]
     ["/suspend"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post {:summary "Suspend a party"
              :openapi {:operationId "SuspendParty"
                        :description
                        (str "Only an active party can be suspended. Any "
                             "other status is refused with 409. A "
                             "`party.suspended` webhook notification follows.")
                        :parameters ^:replace
                                    [shared.parameters/ref-party-id
                                     shared.parameters/ref-bank-id-header
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses (shared.idempotency/with-responses
                          {200 {:description "The suspended party."
                                :body [:ref "SuspendPartyResponse"]
                                :openapi {:links party-api/from-party}}
                           403 (ErrorExamples [#'api-schema/PolicyDenied])
                           404 (ErrorResponse [#'PartyNotFound])
                           409 (ErrorResponse [#'PartyInvalidStatus])})
              :handler commands/suspend-party}}]
     ["/resume"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post {:summary "Resume a suspended party"
              :openapi {:operationId "ResumeParty"
                        :description
                        (str "The party returns to active. A party that is "
                             "not suspended is refused with 409. A "
                             "`party.resumed` webhook notification follows.")
                        :parameters ^:replace
                                    [shared.parameters/ref-party-id
                                     shared.parameters/ref-bank-id-header
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses (shared.idempotency/with-responses
                          {200 {:description "The resumed party."
                                :body [:ref "ResumePartyResponse"]
                                :openapi {:links party-api/from-party}}
                           403 (ErrorExamples [#'api-schema/PolicyDenied])
                           404 (ErrorResponse [#'PartyNotFound])
                           409 (ErrorResponse [#'PartyInvalidStatus])})
              :handler commands/resume-party}}]
     ["/close"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post {:summary "Close a party"
              :openapi {:operationId "CloseParty"
                        :description
                        (str "An active or suspended party can be closed, and "
                             "closing is final. A party that still holds a "
                             "cash account that is not closed is refused "
                             "with 409. A `party.closed` webhook notification "
                             "follows.")
                        :parameters ^:replace
                                    [shared.parameters/ref-party-id
                                     shared.parameters/ref-bank-id-header
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses (shared.idempotency/with-responses
                          {200 {:description "The closed party."
                                :body [:ref "ClosePartyResponse"]
                                :openapi {:links party-api/from-party}}
                           403 (ErrorExamples [#'api-schema/PolicyDenied])
                           404 (ErrorResponse [#'PartyNotFound])
                           409 (ErrorResponse [#'PartyInvalidStatus
                                               #'PartyOpenAccounts])})
              :handler commands/close-party}}]
     ["/merge"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post {:summary "Merge a party into another"
              :openapi {:operationId "MergeParty"
                        :description
                        (str
                         "The party in the path becomes merged and records "
                         "the id of the party it was merged into. It must "
                         "be suspended and hold no cash account that is "
                         "not closed, and the party it merges into must be "
                         "active, or the merge is refused with 409. "
                         "Merging a party into itself is refused with 422. "
                         "A `party.merged` webhook notification follows for "
                         "the merged-away party.")
                        :requestBody {:required true}
                        :parameters ^:replace
                                    [shared.parameters/ref-party-id
                                     shared.parameters/ref-bank-id-header
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :parameters {:body [:ref "MergePartyRequest"]}
              :responses (shared.idempotency/with-responses
                          {200 {:description "The merged party."
                                :body [:ref "MergePartyResponse"]
                                :openapi {:links party-api/from-merged-party}}
                           403 (ErrorExamples [#'api-schema/PolicyDenied])
                           404 (ErrorResponse [#'PartyNotFound])
                           409 (ErrorResponse [#'PartyInvalidStatus
                                               #'PartyOpenAccounts])
                           422 (ErrorResponse [#'PartyMergeIntoSelf])})
              :handler commands/merge-party}}]]]])
