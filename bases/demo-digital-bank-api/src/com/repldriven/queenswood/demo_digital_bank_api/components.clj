(ns com.repldriven.queenswood.demo-digital-bank-api.components
  (:require
    [com.repldriven.mono.utility.interface :refer [vname]]))

(def Phone [:string {:min 7 :max 24 :json-schema/example "07700 900123"}])

(def Name [:string {:min 1 :max 140 :json-schema/example "Amara"}])

(def Code [:re {:json-schema/example "123456"} #"^[0-9]{6}$"])

(def Passcode [:re {:json-schema/example "2468"} #"^[0-9]{4}$"])

(def IsoDate
  [:re {:json-schema/example "1994-03-12"} #"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"])

(def SortCode
  [:re {:json-schema/example "04-00-75"} #"^[0-9]{2}-?[0-9]{2}-?[0-9]{2}$"])

(def AccountNumber [:re {:json-schema/example "31908240"} #"^[0-9]{8}$"])

(def Amount
  [:int {:min 1 :json-schema/example 2500 :description "In minor units"}])

(def Reference [:maybe [:string {:max 18 :json-schema/example "Rent"}]])

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

(def Payee
  [:map
   [:id string?]
   [:name string?]
   [:sort [:maybe string?]]
   [:num [:maybe string?]]
   [:last-paid-at [:maybe string?]]
   [:last-paid-amount [:maybe int?]]])

(def Me
  [:map
   [:user [:ref "User"]]
   [:accounts [:vector [:ref "Account"]]]
   [:txns [:vector [:ref "Transaction"]]]
   [:payees [:vector [:ref "Payee"]]]
   [:products [:vector [:ref "Product"]]]])

(def PayeeCheckRequest
  [:map {:closed true}
   [:name Name]
   [:sort-code SortCode]
   [:account-number AccountNumber]])

(def PayeeCheck
  [:map
   [:check-id [:maybe string?]]
   [:outcome [:enum "match" "close-match" "no-match" "unavailable"]]
   [:name-held [:maybe string?]]])

(def PayeeRef [:map {:closed true} [:id string?]])

(def NewPayee
  [:map {:closed true}
   [:name Name]
   [:sort-code SortCode]
   [:account-number AccountNumber]])

(def PaymentRequest
  [:map {:closed true}
   [:from string?]
   [:payee [:or [:ref "PayeeRef"] [:ref "NewPayee"]]]
   [:amount Amount]
   [:reference {:optional true} Reference]])

(def Payment
  [:map
   [:id string?]
   [:status [:maybe string?]]
   [:from string?]
   [:payee [:ref "Payee"]]
   [:amount int?]
   [:currency [:maybe string?]]
   [:reference [:maybe string?]]
   [:created-at [:maybe string?]]])

(def TransferRequest
  [:map {:closed true}
   [:from string?]
   [:to string?]
   [:amount Amount]
   [:reference {:optional true} Reference]])

(def Transfer
  [:map
   [:id string?]
   [:from string?]
   [:to string?]
   [:amount int?]
   [:currency [:maybe string?]]
   [:reference [:maybe string?]]
   [:created-at [:maybe string?]]])

(def OpenAccountRequest
  [:map {:closed true}
   [:product-id string?]
   [:name {:optional true} Name]
   [:deposit {:optional true}
    [:int {:min 0 :description "In minor units, from the current account"}]]])

(def OpenedAccount
  [:map [:account [:ref "Account"]] [:deposit [:maybe [:ref "Transfer"]]]])

(def Notification
  [:map
   [:id string?]
   [:kind string?]
   [:at string?]
   [:headline string?]
   [:detail [:maybe string?]]
   [:account [:maybe string?]]])

(def Received
  [:map [:notification-id string?] [:status [:enum "accepted" "done"]]])

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
           #'Payee #'Me #'PayeeCheckRequest #'PayeeCheck #'PayeeRef #'NewPayee
           #'PaymentRequest #'Payment #'TransferRequest #'Transfer
           #'OpenAccountRequest #'OpenedAccount #'Notification #'Received
           #'ErrorResponse]))
