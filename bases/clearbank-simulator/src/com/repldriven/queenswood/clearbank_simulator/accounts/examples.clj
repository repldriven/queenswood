(ns com.repldriven.queenswood.clearbank-simulator.accounts.examples
  (:require
    [com.repldriven.queenswood.clearbank-simulator.schema
     :refer [examples-registry]]))

(def VirtualAccountRequest
  {:sortCode "040004"
   :accountNumber "20000001"
   :externalReference "acc.01kprbmgcj35ptc8npmybhh4s8"
   :ownerName "Arthur Dent"
   :currency "GBP"})

(def VirtualAccount
  {:id "va-01943b6e-7a2c-7f3d-9c1e-5b2a4d6e8f10"
   :sortCode "040004"
   :accountNumber "20000001"})

(def registry (examples-registry [#'VirtualAccountRequest #'VirtualAccount]))
