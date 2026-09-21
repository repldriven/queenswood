(ns com.repldriven.queenswood.demo-digital-bank-api.home.components
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.shared.components :as
     shared]))

(def User
  [:map
   [:first string?]
   [:last string?]
   [:phone string?]
   [:verification string?]
   [:member-since [:maybe string?]]])

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

(def Me
  [:map
   [:user [:ref "User"]]
   [:accounts [:vector [:ref "Account"]]]
   [:txns [:vector [:ref "Transaction"]]]
   [:payees [:vector [:ref "Payee"]]]
   [:products [:vector [:ref "Product"]]]])

(def Notification
  [:map
   [:id string?]
   [:kind string?]
   [:at string?]
   [:headline string?]
   [:detail [:maybe string?]]
   [:account [:maybe string?]]])

(def registry (shared/registry-of [#'User #'Transaction #'Me #'Notification]))
