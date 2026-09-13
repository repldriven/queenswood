(ns com.repldriven.queenswood.api.party.routes
  (:require
    [com.repldriven.queenswood.api.party.commands :as commands]
    [com.repldriven.queenswood.api.party.examples :refer
     [IdentificationRejected PartyNotFound PartyInvalidStatus PartyOpenAccounts
      PartyMergeIntoSelf]]
    [com.repldriven.queenswood.api.party.links :as links]
    [com.repldriven.queenswood.api.party.queries :as queries]

    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer [ErrorResponse]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def ^:private list-parties-query-schema
  [:map {:closed true} [:page {:optional true} [:ref "PageQuery"]]])

(def ^:private get-party-query-schema
  [:map {:closed true} [:embed {:optional true} [:ref "PartyEmbedQuery"]]])

(def routes
  [["/parties" {:openapi {:tags ["Parties"]}}
    [""
     {:get {:summary "Retrieve parties"
            :openapi {:operationId "RetrieveParties"
                      :security [{"bearerAuth" ["org:viewer"]}]
                      :parameters ^:replace
                                  [shared.parameters/ref-page
                                   shared.parameters/ref-bank-id-header]}
            :parameters {:query list-parties-query-schema}
            :responses {200 {:body [:ref "PartyList"]}}
            :handler queries/list-parties}
      :post {:summary "Create a new party"
             :openapi {:operationId "CreateParty"
                       :security [{"bearerAuth" ["org:developer"]}]
                       :requestBody {:required true}
                       :parameters ^:replace
                                   [shared.parameters/ref-bank-id-header
                                    shared.parameters/ref-idempotency-key]}
             :interceptors [server/require-idempotency-key
                            bank-idempotency/cache-response]
             :parameters {:body [:ref "CreatePartyRequest"]}
             :responses (shared.idempotency/with-responses
                         {200 {:body [:ref "CreatePartyResponse"]
                               :openapi {:links links/from-party}}
                          422 (ErrorResponse [#'IdentificationRejected])})
             :handler commands/create-party}}]
    ["/{party-id}" {:parameters {:path {:party-id [:ref "PartyId"]}}}
     [""
      {:openapi {:security [{"bearerAuth" ["org:viewer"]}]}
       :get {:summary "Retrieve a party"
             :openapi {:operationId "RetrieveParty"
                       :parameters ^:replace
                                   [shared.parameters/ref-party-id
                                    shared.parameters/ref-party-embed
                                    shared.parameters/ref-bank-id-header]}
             :parameters {:query get-party-query-schema}
             :responses {200 {:body [:ref "PartyDetail"]}
                         404 (ErrorResponse [#'PartyNotFound])}
             :handler queries/get-party}}]
     ["/suspend"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post {:summary "Suspend a party"
              :openapi {:operationId "SuspendParty"
                        :parameters ^:replace
                                    [shared.parameters/ref-party-id
                                     shared.parameters/ref-bank-id-header
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses (shared.idempotency/with-responses
                          {200 {:body [:ref "SuspendPartyResponse"]
                                :openapi {:links links/from-party}}
                           404 (ErrorResponse [#'PartyNotFound])
                           409 (ErrorResponse [#'PartyInvalidStatus])})
              :handler commands/suspend-party}}]
     ["/resume"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post {:summary "Resume a suspended party"
              :openapi {:operationId "ResumeParty"
                        :parameters ^:replace
                                    [shared.parameters/ref-party-id
                                     shared.parameters/ref-bank-id-header
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses (shared.idempotency/with-responses
                          {200 {:body [:ref "ResumePartyResponse"]
                                :openapi {:links links/from-party}}
                           404 (ErrorResponse [#'PartyNotFound])
                           409 (ErrorResponse [#'PartyInvalidStatus])})
              :handler commands/resume-party}}]
     ["/close"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post {:summary "Close a party"
              :openapi {:operationId "CloseParty"
                        :parameters ^:replace
                                    [shared.parameters/ref-party-id
                                     shared.parameters/ref-bank-id-header
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses (shared.idempotency/with-responses
                          {200 {:body [:ref "ClosePartyResponse"]
                                :openapi {:links links/from-party}}
                           404 (ErrorResponse [#'PartyNotFound])
                           409 (ErrorResponse [#'PartyInvalidStatus
                                               #'PartyOpenAccounts])})
              :handler commands/close-party}}]
     ["/merge"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post {:summary "Merge a party into another"
              :openapi {:operationId "MergeParty"
                        :requestBody {:required true}
                        :parameters ^:replace
                                    [shared.parameters/ref-party-id
                                     shared.parameters/ref-bank-id-header
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :parameters {:body [:ref "MergePartyRequest"]}
              :responses (shared.idempotency/with-responses
                          {200 {:body [:ref "MergePartyResponse"]
                                :openapi {:links links/from-merged-party}}
                           404 (ErrorResponse [#'PartyNotFound])
                           409 (ErrorResponse [#'PartyInvalidStatus
                                               #'PartyOpenAccounts])
                           422 (ErrorResponse [#'PartyMergeIntoSelf])})
              :handler commands/merge-party}}]]]])
