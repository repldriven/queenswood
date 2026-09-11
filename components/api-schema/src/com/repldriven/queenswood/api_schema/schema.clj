(ns com.repldriven.queenswood.api-schema.schema
  (:require
    [com.repldriven.mono.utility.interface :refer [vname]]))

(defn components-registry
  [vars]
  (reduce (fn [m v] (assoc m (vname v) @v)) {} vars))

(defn id-schema
  [title prefix example]
  [:re
   {:title title :json-schema/example example}
   (re-pattern (str "^" prefix "\\.[0-9a-hjkmnp-tv-z]{26}$"))])

(defn examples-registry
  [examples]
  (reduce (fn [m v] (assoc m (vname v) @v)) {} examples))

(def ErrorResponseSchema
  [:map
   [:title string?]
   [:type string?]
   [:status int?]
   [:detail {:optional true} string?]])

(defn ErrorResponse
  [examples]
  {:content {"application/json"
             {:schema [:ref "ErrorResponse"]
              :examples (reduce (fn [m v]
                                  (let [v' (vname v)]
                                    (assoc m
                                           v'
                                           {"$ref" (str "#/components/examples/"
                                                        v')})))
                                {}
                                examples)}}})
