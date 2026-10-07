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
   [:verification {:optional true} [:maybe string?]]
   [:hand-off-url {:optional true} [:maybe string?]]])

(def CodeRequest [:map {:closed true} [:code shared/Code]])

(def DetailsRequest
  [:map {:closed true}
   [:given-name shared/Name]
   [:family-name shared/Name]
   [:email
    [:re {:json-schema/example "amara@example.com"}
     #"^[^\s@]{1,64}@[^\s@]{1,255}$"]]])

(def PasscodeRequest [:map {:closed true} [:passcode shared/Passcode]])

(def registry
  (shared/registry-of [#'StartSignUpRequest #'SignUp #'CodeRequest
                       #'DetailsRequest #'PasscodeRequest]))
