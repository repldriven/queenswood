(ns com.repldriven.queenswood.party-api.links
  "OpenAPI 3 `links` objects for party responses.")

(def from-party
  "Links available on any response whose body is a `Party`
  (create, suspend, resume, close)."
  {"GetParty" {:operationId "RetrieveParty"
               :parameters {"party-id" "$response.body#/party-id"}}})

(def from-merged-party
  "Links available on a `MergePartyResponse` — `GetParty` for the
  merged-away record plus `GetMergedIntoParty`, following the
  `merged-into-party-id` pointer to the survivor."
  (assoc from-party
         "GetMergedIntoParty"
         {:operationId "RetrieveParty"
          :parameters {"party-id" "$response.body#/merged-into-party-id"}}))
