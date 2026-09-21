(ns com.repldriven.queenswood.demo-digital-bank-api.account.components
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.shared.components :as
     shared]))

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

(def Product
  [:map
   [:id string?]
   [:kind string?]
   [:name [:maybe string?]]
   [:product-type [:maybe string?]]
   [:rate-bps int?]])

(def OpenAccountRequest
  [:map {:closed true}
   [:product-id string?]
   [:name {:optional true} shared/Name]
   [:deposit {:optional true}
    [:int {:min 0 :description "In minor units, from the current account"}]]])

(def OpenedAccount
  [:map [:account [:ref "Account"]] [:deposit [:maybe [:ref "Transfer"]]]])

(def registry
  (shared/registry-of [#'Account #'Product #'OpenAccountRequest
                       #'OpenedAccount]))
