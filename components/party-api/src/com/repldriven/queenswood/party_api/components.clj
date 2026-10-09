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

(def Party
  [:map {:json-schema/example examples/Party}
   [:bank-id [:ref "BankId"]]
   [:party-id [:ref "PartyId"]]
   [:party-type [:ref "PartyType"]]
   [:display-name {:optional true} [:ref "Name"]]
   [:status [:ref "PartyStatus"]]
   [:merged-into-party-id {:optional true} [:maybe [:ref "PartyId"]]]
   [:external-reference {:optional true} [:maybe [:ref "ExternalReference"]]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at {:optional true} [:ref "Timestamp"]]])

(def ExternalReference
  "The customer's own id for the person, unique within the bank."
  [:string
   {:min 1
    :max 128
    :json-schema/example "cust-4471"
    :json-schema/description
    "The customer's own id for the person, unique within the bank."}])

(def CreatePartyRequest
  "A person is registered by their legal name alone: what proves who they
  are they give to the identity provider (ADR-0045), so the map is closed
  and a request carrying anything else is refused."
  [:map {:closed true :json-schema/example examples/CreatePartyRequest}
   [:party-type
    [:enum
     {:json-schema coercion/party-type-json-schema
      :decode/api coercion/decode-party-type}
     :party-type-person]]
   [:legal-name [:ref "Name"]]
   [:display-name {:optional true} [:ref "Name"]]
   [:external-reference {:optional true} [:ref "ExternalReference"]]])

(def PartyDetail
  "Party-by-id detail: the summary fields, and the party's legal name
  where `embed[legal-name]` asks for it."
  [:map {:json-schema/example examples/Party}
   [:bank-id [:ref "BankId"]]
   [:party-id [:ref "PartyId"]]
   [:party-type [:ref "PartyType"]]
   [:display-name {:optional true} [:ref "Name"]]
   [:status [:ref "PartyStatus"]]
   [:merged-into-party-id {:optional true} [:maybe [:ref "PartyId"]]]
   [:external-reference {:optional true} [:maybe [:ref "ExternalReference"]]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at {:optional true} [:ref "Timestamp"]]
   [:legal-name {:optional true} [:maybe :string]]])

(def PartyEmbedQuery
  "Nested `embed` deepObject query parameter for the party detail
  endpoint. Wire form is `embed[legal-name]=true`, nested into
  `{:legal-name …}` by the `nest-bracket-query-params`
  interceptor before validation. Set, it opts the party's legal name
  into the response; omitted, the GET returns just the summary party."
  [:map {:closed true}
   [:legal-name
    {:optional true :json-schema/description "Embed the party's legal name"}
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
   [:failure-reason {:optional true} [:maybe string?]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at {:optional true} [:ref "Timestamp"]]])

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
   [#'PartyType #'PartyStatus #'Party #'ExternalReference #'PartyDetail
    #'PartyEmbedQuery #'CreatePartyRequest #'CreatePartyResponse #'PartyList
    #'MergePartyRequest #'MergePartyResponse #'SuspendPartyResponse
    #'ResumePartyResponse #'ClosePartyResponse #'VerificationId
    #'VerificationSessionId #'VerificationStatus #'VerificationChannel
    #'VerificationSessionStatus #'HandOffType #'VerificationCriterionState
    #'ReturnUrl #'OpenVerificationSessionRequest #'HandOff #'VerificationSession
    #'VerificationCriterion #'Verification]))

(def ^:private party-keys (into [] (comp (filter vector?) (map first)) Party))

(defn ->body
  [party]
  (select-keys party party-keys))

(def ^:private party-detail-keys
  (into [] (comp (filter vector?) (map first)) PartyDetail))

(defn ->detail-body
  [party]
  (select-keys party party-detail-keys))

(def ^:private encode-party
  (schema/api-encoder Party (merge schema/registry registry)))

(defn ->wire-body
  [party]
  (encode-party (->body party)))

(def ^:private session-keys
  [:session-id :party-id :channel :return-url :status :hand-off
   :failure-reason :created-at :updated-at])

(defn ->session-body
  [session]
  (cond-> (select-keys session session-keys)
          (:hand-off session)
          (update :hand-off
                  (fn [{:keys [kind url expires-at]}]
                    {:type kind :url url :expires-at expires-at}))))

(def ^:private encode-session
  (schema/api-encoder VerificationSession (merge schema/registry registry)))

(defn ->session-wire-body
  [session]
  (encode-session (->session-body session)))

(defn- criterion-body
  [criterion]
  (let [{:keys [verification screening status reason]} criterion]
    (cond-> {:name (str/replace (name (or verification screening))
                                #"^idv-(verification|screening)-"
                                "")
             :kind (if verification "verification" "screening")
             :state status}
            reason
            (assoc :reason reason))))

(defn ->verification-body
  [party-id verification]
  (-> verification
      (select-keys [:verification-id :status])
      (assoc :party-id party-id
             :criteria (mapv criterion-body (:criteria verification)))))
