(ns com.repldriven.queenswood.demo-digital-bank-api.platform.components
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.shared.components :as
     shared]))

(def Received
  [:map [:notification-id string?] [:status [:enum "accepted" "done"]]])

(def registry (shared/registry-of [#'Received]))
