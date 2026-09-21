(ns com.repldriven.queenswood.demo-digital-bank-api.session.components
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.shared.components :as
     shared]))

(def SignInRequest
  [:map {:closed true} [:phone shared/Phone] [:passcode shared/Passcode]])

(def Session [:map [:token string?] [:expires-at string?]])

(def registry (shared/registry-of [#'SignInRequest #'Session]))
