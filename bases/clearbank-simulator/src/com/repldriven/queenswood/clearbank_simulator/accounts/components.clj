(ns com.repldriven.queenswood.clearbank-simulator.accounts.components
  (:require
    [com.repldriven.queenswood.clearbank-simulator.accounts.examples :as
     examples]

    [com.repldriven.queenswood.clearbank-simulator.schema :refer
     [components-registry]]))

(def VirtualAccountRequest
  [:map
   {:json-schema/example examples/VirtualAccountRequest}
   [:sortCode string?]
   [:accountNumber string?]
   [:externalReference string?]
   [:ownerName {:optional true} [:maybe string?]]
   [:currency {:optional true} [:maybe string?]]])

(def VirtualAccount
  [:map
   {:json-schema/example examples/VirtualAccount}
   [:id string?]
   [:sortCode string?]
   [:accountNumber string?]])

(def registry (components-registry [#'VirtualAccountRequest #'VirtualAccount]))
