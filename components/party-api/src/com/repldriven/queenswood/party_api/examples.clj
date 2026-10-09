(ns com.repldriven.queenswood.party-api.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [examples-registry]]))

(def PartyNotFound
  {:value {:title "REJECTED"
           :type ":party/not-found"
           :status 404
           :detail "Party not found"}})

(def ExternalReferenceTaken
  {:value {:title "REJECTED"
           :type ":party/external-reference-taken"
           :status 409
           :detail "A party already has this external reference"}})

(def PartyInvalidStatus
  {:value {:title "REJECTED"
           :type ":party/invalid-status"
           :status 409
           :detail "Party is not in a valid state for this action"}})

(def PartyOpenAccounts
  {:value {:title "REJECTED"
           :type ":party/open-accounts"
           :status 409
           :detail "Party has open cash accounts"}})

(def PartyMergeIntoSelf
  {:value {:title "REJECTED"
           :type ":party/merge-into-self"
           :status 422
           :detail "Cannot merge a party into itself"}})

(def VerificationNotFound
  {:value {:title "REJECTED"
           :type ":idv/not-found"
           :status 404
           :detail "No verification for this party"}})

(def VerificationSessionNotFound
  {:value {:title "REJECTED"
           :type ":idv/session-not-found"
           :status 404
           :detail "No such verification session"}})

(def VerificationInvalidStatus
  {:value {:title "REJECTED"
           :type ":idv/invalid-status"
           :status 409
           :detail "IDV is not awaiting a verification"}})

(def UnsupportedChannel
  {:value {:title "REJECTED"
           :type ":idv/unsupported-channel"
           :status 422
           :detail "The identity provider does not offer this channel"}})

(def MissingEmail
  {:value {:title "REJECTED"
           :type ":idv/missing-email"
           :status 422
           :detail "The identity provider needs the person's email"}})

(def registry
  (examples-registry [#'PartyNotFound #'ExternalReferenceTaken
                      #'PartyInvalidStatus #'PartyOpenAccounts
                      #'PartyMergeIntoSelf #'VerificationNotFound
                      #'VerificationSessionNotFound #'VerificationInvalidStatus
                      #'UnsupportedChannel #'MissingEmail]))

(def Party
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :party-id (schema/id-examples "PartyId")
   :party-type :person
   :display-name "Arthur Phillip Dent"
   :status :pending
   :external-reference "cust-4471"
   :created-at "2025-01-01T00:00:00Z"
   :updated-at "2025-01-01T00:00:00Z"})

(def PartyId (:party-id Party))

(def PartyList {:items [Party]})

(def CreatePartyRequest
  {:party-type :person
   :legal-name "Arthur Phillip Dent"
   :display-name "Arthur Phillip Dent"
   :external-reference "cust-4471"})

(def MergePartyRequest {:into-party-id "pty.01kprbmgcj35ptc8npmybhh4sb"})


(def VerificationSessionId "ses.01kprbmgcj35ptc8npmybhh4sc")

(def VerificationId "idv.01kprbmgcj35ptc8npmybhh4sd")

(def OpenVerificationSessionRequest
  {:channel :web
   :return-url "https://app.example.com/onboarding/verified"
   :email "a.dent@example.com"})

(def HandOff
  {:type :url
   :url (str "https://verify.example.com/flow/onboarding"
             "?zypheVr=cf52e18e&zypheToken=eyJhbGci")
   :expires-at "2025-01-01T00:15:00Z"})

(def VerificationSession
  {:session-id VerificationSessionId
   :party-id PartyId
   :channel :web
   :return-url "https://app.example.com/onboarding/verified"
   :status :ready
   :hand-off HandOff
   :created-at "2025-01-01T00:00:00Z"
   :updated-at "2025-01-01T00:00:02Z"})

(def VerificationCriterion
  {:name :address
   :kind :verification
   :state :outstanding
   :reason "A person's address must be verified"})

(def Verification
  {:verification-id VerificationId
   :party-id PartyId
   :status :pending
   :criteria [{:name :identity :kind :verification :state :established}
              VerificationCriterion]})
