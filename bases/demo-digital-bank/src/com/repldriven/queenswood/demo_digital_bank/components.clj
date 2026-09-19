(ns com.repldriven.queenswood.demo-digital-bank.components
  (:require
    [com.repldriven.mono.utility.interface :refer [vname]]))

(def Phone [:string {:min 7 :max 24 :json-schema/example "07700 900123"}])

(def Name [:string {:min 1 :max 140 :json-schema/example "Amara"}])

(def Code [:re {:json-schema/example "123456"} #"^[0-9]{6}$"])

(def Passcode [:re {:json-schema/example "2468"} #"^[0-9]{4}$"])

(def IsoDate
  [:re {:json-schema/example "1994-03-12"} #"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"])

(def StartSignUpRequest [:map {:closed true} [:phone Phone]])

(def SignUp
  [:map
   [:id string?]
   [:status string?]
   [:party-id {:optional true} [:maybe string?]]
   [:verification {:optional true} [:maybe string?]]])

(def CodeRequest [:map {:closed true} [:code Code]])

(def Address
  [:map {:closed true}
   [:building-number {:optional true} Name]
   [:street Name]
   [:town Name]
   [:postcode Name]
   [:country {:optional true} [:re #"^[A-Z]{3}$"]]])

(def NationalIdentifier
  [:map {:closed true}
   [:type {:optional true} string?]
   [:value [:string {:min 1 :max 64}]]
   [:issuing-country {:optional true} [:re #"^[A-Z]{2}$"]]])

(def DetailsRequest
  [:map {:closed true}
   [:given-name Name]
   [:family-name Name]
   [:date-of-birth IsoDate]
   [:nationality {:optional true} [:re #"^[A-Z]{2}$"]]
   [:address [:ref "Address"]]
   [:national-identifier [:ref "NationalIdentifier"]]])

(def PasscodeRequest [:map {:closed true} [:passcode Passcode]])

(def SignInRequest [:map {:closed true} [:phone Phone] [:passcode Passcode]])

(def Session [:map [:token string?] [:expires-at string?]])

(def User
  [:map
   [:first string?]
   [:last string?]
   [:phone string?]
   [:verification string?]
   [:member-since [:maybe string?]]])

(def Account
  [:map
   [:id string?]
   [:kind string?]
   [:name string?]
   [:type string?]
   [:status [:maybe string?]]
   [:balance int?]
   [:posted int?]
   [:currency [:maybe string?]]
   [:sort [:maybe string?]]
   [:num [:maybe string?]]
   [:spark [:vector int?]]])

(def Transaction
  [:map
   [:id string?]
   [:acct string?]
   [:who string?]
   [:cat string?]
   [:amount int?]
   [:currency [:maybe string?]]
   [:status [:maybe string?]]
   [:at string?]
   [:ref [:maybe string?]]])

(def Product
  [:map
   [:id string?]
   [:kind string?]
   [:name [:maybe string?]]
   [:product-type [:maybe string?]]
   [:rate-bps int?]])

(def Me
  [:map
   [:user [:ref "User"]]
   [:accounts [:vector [:ref "Account"]]]
   [:txns [:vector [:ref "Transaction"]]]
   [:payees [:vector any?]]
   [:products [:vector [:ref "Product"]]]])

(def ErrorResponse
  [:map
   [:title string?]
   [:type string?]
   [:status int?]
   [:detail {:optional true} string?]])

(def registry
  (reduce (fn [m v] (assoc m (vname v) @v))
          {}
          [#'StartSignUpRequest #'SignUp #'CodeRequest #'Address
           #'NationalIdentifier #'DetailsRequest #'PasscodeRequest
           #'SignInRequest #'Session #'User #'Account #'Transaction #'Product
           #'Me #'ErrorResponse]))
