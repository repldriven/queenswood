(ns com.repldriven.queenswood.demo-digital-bank-api.payment.components
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.shared.components :as
     shared]))

(def Payee
  [:map
   [:id string?]
   [:name string?]
   [:sort [:maybe string?]]
   [:num [:maybe string?]]
   [:last-paid-at [:maybe string?]]
   [:last-paid-amount [:maybe int?]]])

(def PayeeCheckRequest
  [:map {:closed true}
   [:name shared/Name]
   [:sort-code shared/SortCode]
   [:account-number shared/AccountNumber]])

(def PayeeCheck
  [:map
   [:check-id [:maybe string?]]
   [:outcome [:enum "match" "close-match" "no-match" "unavailable"]]
   [:name-held [:maybe string?]]])

(def PayeeRef [:map {:closed true} [:id string?]])

(def NewPayee
  [:map {:closed true}
   [:name shared/Name]
   [:sort-code shared/SortCode]
   [:account-number shared/AccountNumber]])

(def PaymentRequest
  [:map {:closed true}
   [:from string?]
   [:payee [:or [:ref "PayeeRef"] [:ref "NewPayee"]]]
   [:amount shared/Amount]
   [:reference {:optional true} shared/Reference]])

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
   [:amount shared/Amount]
   [:reference {:optional true} shared/Reference]])

(def Transfer
  [:map
   [:id string?]
   [:from string?]
   [:to string?]
   [:amount int?]
   [:currency [:maybe string?]]
   [:reference [:maybe string?]]
   [:created-at [:maybe string?]]])

(def registry
  (shared/registry-of [#'Payee #'PayeeCheckRequest #'PayeeCheck #'PayeeRef
                       #'NewPayee #'PaymentRequest #'Payment #'TransferRequest
                       #'Transfer]))
