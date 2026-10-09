(ns com.repldriven.queenswood.me-api.components
  (:require
    [com.repldriven.queenswood.me-api.coercion :as coercion]
    [com.repldriven.queenswood.me-api.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry]]))

(def UserId (schema/id-schema "UserId" "usr" examples/UserId))

(def MemberId (schema/id-schema "MemberId" "mem" examples/MemberId))

(def IdentityProvider
  (coercion/identity-provider-enum-schema {:json-schema/example "google"}))

(def Role (coercion/role-enum-schema {:json-schema/example "owner"}))

(def Me
  [:map
   {:json-schema/example examples/Me
    :description
    "The signed-in person: their user record, and whether they are an
    operator. Their members are at `/v1/me/members`."}
   [:user-id [:ref "UserId"]]
   [:issuer string?]
   [:sub string?]
   [:email string?]
   [:name [:ref "Name"]]
   [:avatar-url {:optional true} string?]
   [:identity-provider [:ref "IdentityProvider"]]
   [:operator boolean?]
   [:created-at [:ref "Timestamp"]]
   [:updated-at {:optional true} [:ref "Timestamp"]]])

(def registry
  (components-registry [#'UserId #'MemberId #'IdentityProvider #'Role #'Me]))
