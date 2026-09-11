(ns com.repldriven.queenswood.api.party.components
  (:require
    [com.repldriven.queenswood.api.party.coercion :as coercion]
    [com.repldriven.queenswood.api.party.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry]]))

(def PartyId (schema/id-schema "PartyId" "pty" examples/PartyId))

(def PartyType
  (coercion/party-type-enum-schema {:json-schema/example "person"}))

(def PartyStatus
  (coercion/party-status-enum-schema {:json-schema/example "active"}))

(def IdentifierType
  (coercion/identifier-type-enum-schema {:json-schema/example "passport"}))

(def Party
  [:map {:json-schema/example examples/Party}
   [:bank-id [:ref "BankId"]]
   [:party-id [:ref "PartyId"]]
   [:type [:ref "PartyType"]]
   [:display-name [:ref "Name"]]
   [:status [:ref "PartyStatus"]]
   [:merged-into-party-id {:optional true} [:maybe [:ref "PartyId"]]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at [:ref "Timestamp"]]])

(def NationalIdentifier
  [:map {:closed true}
   [:type [:ref "IdentifierType"]]
   [:value [:ref "NationalIdentifierValue"]]
   [:issuing-country [:ref "CountryCode"]]])

(def Address
  "Address shape mirrors the Entrust/Onfido applicant address
  object. `country` is ISO 3166-1 alpha-3 to match what the
  applicants API expects; this differs from `nationality` (alpha-2)
  on PersonIdentification — kept separate so the adapter doesn't
  need a code-table conversion at the edge."
  [:map {:closed true :json-schema/example examples/Address}
   [:flat-number {:optional true} [:ref "Name"]]
   [:building-number {:optional true} [:ref "Name"]]
   [:building-name {:optional true} [:ref "Name"]]
   [:street [:ref "Name"]]
   [:sub-street {:optional true} [:ref "Name"]]
   [:town [:ref "Name"]]
   [:state {:optional true} [:ref "Name"]]
   [:postcode [:ref "Name"]]
   [:country [:ref "Country3Code"]]
   [:start-date {:optional true} [:ref "Date"]]])

(def CreatePartyRequest
  [:map {:json-schema/example examples/CreatePartyRequest}
   [:type
    [:enum
     {:json-schema coercion/party-type-json-schema
      :decode/api coercion/decode-party-type}
     :party-type-person]]
   [:display-name [:ref "Name"]]
   [:given-name [:ref "Name"]]
   [:middle-names {:optional true} [:maybe [:ref "Name"]]]
   [:family-name [:ref "Name"]]
   [:date-of-birth [:ref "DateOfBirth"]]
   [:nationality [:ref "CountryCode"]]
   [:address [:ref "Address"]]
   [:national-identifier [:ref "NationalIdentifier"]]])

(def PartyDetail
  "Party-by-id detail: the summary fields plus the person
  identification and national identifier the record was created with.
  Open map — internal/organisation parties carry only the summary, and
  email/phone aren't persisted so they're never present."
  [:map {:json-schema/example examples/Party}
   [:bank-id [:ref "BankId"]]
   [:party-id [:ref "PartyId"]]
   [:type [:ref "PartyType"]]
   [:display-name [:ref "Name"]]
   [:status [:ref "PartyStatus"]]
   [:merged-into-party-id {:optional true} [:maybe [:ref "PartyId"]]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at [:ref "Timestamp"]]
   ;; Enriched fields are deliberately lenient. The merged record carries
   ;; raw protobuf values — an integer date-of-birth, a keyword
   ;; identifier type, a default-filled address — and putting those
   ;; through strict refs (DateOfBirth's int→ISO encoder, the closed
   ;; Address / NationalIdentifier schemas) trips response coercion. Held
   ;; as plain types here, they pass straight through; the console
   ;; formats them for display.
   [:given-name {:optional true} [:maybe :string]]
   [:middle-names {:optional true} [:maybe :string]]
   [:family-name {:optional true} [:maybe :string]]
   [:date-of-birth {:optional true} [:maybe :int]]
   [:nationality {:optional true} [:maybe :string]]
   [:address {:optional true} [:maybe [:map]]]
   [:national-identifier {:optional true} [:maybe [:map]]]])

(def PartyEmbedQuery
  "Nested `embed` deepObject query parameter for the party detail
  endpoint. Wire form is
  `embed[person-identification]=true&embed[address]=true&embed[national-identifier]=true`,
  nested into `{:person-identification …}` by the
  `nest-bracket-query-params` interceptor before validation. Each flag
  opts the corresponding sub-record into the response; omitted, the GET
  returns just the summary party."
  [:map {:closed true}
   [:person-identification {:optional true} boolean?]
   [:address {:optional true} boolean?]
   [:national-identifier {:optional true} boolean?]])

(def CreatePartyResponse [:ref "Party"])

(def MergePartyRequest
  [:map {:closed true :json-schema/example examples/MergePartyRequest}
   [:into-party-id [:ref "PartyId"]]])

(def MergePartyResponse [:ref "Party"])

(def SuspendPartyResponse [:ref "Party"])

(def ResumePartyResponse [:ref "Party"])

(def ClosePartyResponse [:ref "Party"])

(def PartyList
  [:map {:json-schema/example examples/PartyList}
   [:parties [:vector [:ref "Party"]]]
   [:links {:optional true}
    [:map
     [:next {:optional true} string?]
     [:prev {:optional true} string?]]]])

(def registry
  (components-registry
   [#'PartyId #'PartyType #'PartyStatus #'IdentifierType #'Party #'PartyDetail
    #'PartyEmbedQuery #'NationalIdentifier #'Address #'CreatePartyRequest
    #'CreatePartyResponse #'PartyList #'MergePartyRequest #'MergePartyResponse
    #'SuspendPartyResponse #'ResumePartyResponse #'ClosePartyResponse]))
