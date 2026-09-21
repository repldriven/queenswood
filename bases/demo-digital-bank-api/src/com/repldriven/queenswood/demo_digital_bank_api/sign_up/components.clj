(ns com.repldriven.queenswood.demo-digital-bank-api.sign-up.components
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.shared.components :as
     shared]))

(def StartSignUpRequest [:map {:closed true} [:phone shared/Phone]])

(def SignUp
  [:map
   [:id string?]
   [:status string?]
   [:party-id {:optional true} [:maybe string?]]
   [:verification {:optional true} [:maybe string?]]])

(def CodeRequest [:map {:closed true} [:code shared/Code]])

(def Address
  [:map {:closed true}
   [:building-number {:optional true} shared/Name]
   [:street shared/Name]
   [:town shared/Name]
   [:postcode shared/Name]
   [:country {:optional true} [:re #"^[A-Z]{3}$"]]])

(def NationalIdentifier
  [:map {:closed true}
   [:type {:optional true} string?]
   [:value [:string {:min 1 :max 64}]]
   [:issuing-country {:optional true} [:re #"^[A-Z]{2}$"]]])

(def DetailsRequest
  [:map {:closed true}
   [:given-name shared/Name]
   [:family-name shared/Name]
   [:date-of-birth shared/IsoDate]
   [:nationality {:optional true} [:re #"^[A-Z]{2}$"]]
   [:address [:ref "Address"]]
   [:national-identifier [:ref "NationalIdentifier"]]])

(def PasscodeRequest [:map {:closed true} [:passcode shared/Passcode]])

(def registry
  (shared/registry-of [#'StartSignUpRequest #'SignUp #'CodeRequest #'Address
                       #'NationalIdentifier #'DetailsRequest
                       #'PasscodeRequest]))
