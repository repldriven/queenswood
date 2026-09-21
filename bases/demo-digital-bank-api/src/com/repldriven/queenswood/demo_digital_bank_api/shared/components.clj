(ns com.repldriven.queenswood.demo-digital-bank-api.shared.components
  (:require
    [com.repldriven.mono.utility.interface :refer [vname]]))

(defn registry-of
  "A schema registry keyed by each var's name, as `$ref`s name them."
  [vars]
  (reduce (fn [m v] (assoc m (vname v) @v)) {} vars))

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

(def ErrorResponse
  [:map
   [:title string?]
   [:type string?]
   [:status int?]
   [:detail {:optional true} string?]])

(def registry (registry-of [#'ErrorResponse]))
