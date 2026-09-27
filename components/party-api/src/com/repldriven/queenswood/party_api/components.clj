(ns com.repldriven.queenswood.party-api.components
  (:require
    [com.repldriven.queenswood.party-api.coercion :as coercion]
    [com.repldriven.queenswood.party-api.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry list-schema]]

    [clojure.string :as str]))

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
   [:person-identification
    {:optional true
     :json-schema/description
     "Embed person identification (names, date of birth, nationality)"}
    boolean?]
   [:address {:optional true :json-schema/description "Embed address"}
    boolean?]
   [:national-identifier
    {:optional true :json-schema/description "Embed national identifier"}
    boolean?]])

(def CreatePartyResponse [:ref "Party"])

(def MergePartyRequest
  [:map {:closed true :json-schema/example examples/MergePartyRequest}
   [:into-party-id [:ref "PartyId"]]])

(def MergePartyResponse [:ref "Party"])

(def SuspendPartyResponse [:ref "Party"])

(def ResumePartyResponse [:ref "Party"])

(def ClosePartyResponse [:ref "Party"])

(def PartyList (list-schema "Party" examples/PartyList))

(def VerificationId
  (schema/id-schema "VerificationId" "idv" examples/VerificationId))

(def VerificationSessionId
  (schema/id-schema "VerificationSessionId"
                    "ses"
                    examples/VerificationSessionId))

(def VerificationStatus
  (coercion/verification-status-enum-schema {:json-schema/example "pending"}))

(def VerificationChannel
  (coercion/verification-channel-enum-schema {:json-schema/example "web"}))

(def VerificationSessionStatus
  (coercion/verification-session-status-enum-schema {:json-schema/example
                                                     "ready"}))

(def HandOffType
  (coercion/hand-off-type-enum-schema {:json-schema/example "url"}))

(def VerificationCriterionState
  (coercion/criterion-state-enum-schema {:json-schema/example "established"}))

(def ReturnUrl
  "Where the provider returns the person: an https page, an http page on
  the loopback interface, or a link into the tenant's app."
  [:re
   {:title "ReturnUrl"
    :json-schema/example "https://app.example.com/onboarding/verified"}
   #"^(https://[^\s]+|http://(localhost|127\.0\.0\.1|\[::1\])(:[0-9]+)?([/?#][^\s]*)?|(?!https?:)[a-zA-Z][a-zA-Z0-9+.-]*:[^\s]+)$"])

(def OpenVerificationSessionRequest
  [:map
   {:closed true :json-schema/example examples/OpenVerificationSessionRequest}
   [:channel [:ref "VerificationChannel"]]
   [:return-url [:ref "ReturnUrl"]]
   [:email {:optional true} [:ref "EmailAddress"]]])

(def HandOff
  [:map {:json-schema/example examples/HandOff}
   [:type [:ref "HandOffType"]]
   [:url :string]
   [:expires-at [:ref "Timestamp"]]])

(def VerificationSession
  [:map {:json-schema/example examples/VerificationSession}
   [:session-id [:ref "VerificationSessionId"]]
   [:party-id [:ref "PartyId"]]
   [:channel [:ref "VerificationChannel"]]
   [:return-url :string]
   [:status [:ref "VerificationSessionStatus"]]
   [:hand-off {:optional true} [:ref "HandOff"]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at [:ref "Timestamp"]]])

(def VerificationCriterion
  [:map {:json-schema/example examples/VerificationCriterion}
   [:name
    [:enum {:json-schema/example "address"}
     "identity" "liveness" "claimed-identity" "address" "sanctions" "pep"]]
   [:kind
    [:enum {:json-schema/example "verification"} "verification"
     "screening"]]
   [:state [:ref "VerificationCriterionState"]]
   [:reason {:optional true} :string]])

(def Verification
  [:map {:json-schema/example examples/Verification}
   [:verification-id [:ref "VerificationId"]]
   [:party-id [:ref "PartyId"]]
   [:status [:ref "VerificationStatus"]]
   [:criteria [:vector [:ref "VerificationCriterion"]]]])

(def registry
  (components-registry
   [#'PartyType #'PartyStatus #'IdentifierType #'Party #'PartyDetail
    #'PartyEmbedQuery #'NationalIdentifier #'Address #'CreatePartyRequest
    #'CreatePartyResponse #'PartyList #'MergePartyRequest #'MergePartyResponse
    #'SuspendPartyResponse #'ResumePartyResponse #'ClosePartyResponse
    #'VerificationId #'VerificationSessionId #'VerificationStatus
    #'VerificationChannel #'VerificationSessionStatus #'HandOffType
    #'VerificationCriterionState #'ReturnUrl #'OpenVerificationSessionRequest
    #'HandOff #'VerificationSession #'VerificationCriterion #'Verification]))

(def ^:private party-keys (into [] (comp (filter vector?) (map first)) Party))

(defn ->body
  [party]
  (select-keys party party-keys))

(def ^:private encode-party
  (schema/api-encoder Party (merge schema/registry registry)))

(defn ->wire-body
  [party]
  (encode-party (->body party)))

(def ^:private session-keys
  [:session-id :party-id :channel :return-url :status :hand-off :created-at
   :updated-at])

(defn ->session-body
  [session]
  (cond-> (select-keys session session-keys)
          (:hand-off session)
          (update :hand-off select-keys [:type :url :expires-at])))

(def ^:private encode-session
  (schema/api-encoder VerificationSession (merge schema/registry registry)))

(defn ->session-wire-body
  [session]
  (encode-session (->session-body session)))

(defn- criterion-body
  [criterion]
  (let [{:keys [verification screening state reason]} criterion]
    (cond-> {:name (str/replace (name (or verification screening))
                                #"^idv-(verification|screening)-"
                                "")
             :kind (if verification "verification" "screening")
             :state state}
            reason
            (assoc :reason reason))))

(defn ->verification-body
  [party-id verification]
  (-> verification
      (select-keys [:verification-id :status])
      (assoc :party-id party-id
             :criteria (mapv criterion-body (:criteria verification)))))
