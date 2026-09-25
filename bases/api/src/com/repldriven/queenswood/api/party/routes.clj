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
     [IdentificationRejected PartyInvalidStatus PartyMergeIntoSelf PartyNotFound
      PartyOpenAccounts]]

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
                            "pending while its identity is verified, then "
                            "becomes active if verification accepts it or "
                            "rejected if not, with a `party.opened` or "
                            "`party.rejected` webhook notification. A national "
                            "identifier another party already holds is refused "
                            "with 422.")
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
               422 (ErrorResponse [#'IdentificationRejected])})
             :handler commands/create-party}}]
    ["/{party-id}" {:parameters {:path {:party-id [:ref "PartyId"]}}}
     [""
      {:openapi {:security [{"bearerAuth" ["org:viewer"]}]}
       :get {:summary "Retrieve a party"
             :openapi {:operationId "RetrieveParty"
                       :description
                       (str "Set `embed[person-identification]`, "
                            "`embed[address]` or `embed[national-identifier]` "
                            "to include those records with the party. A "
                            "merged party names the party it was merged into.")
                       :parameters ^:replace
                                   [shared.parameters/ref-party-id
                                    shared.parameters/ref-party-embed
                                    shared.parameters/ref-bank-id-header]}
             :parameters {:query get-party-query-schema}
             :responses {200 {:description
                              "The party, with any records the request embeds."
                              :body [:ref "PartyDetail"]}
                         404 (ErrorResponse [#'PartyNotFound])}
             :handler queries/get-party}}]
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
