(ns com.repldriven.queenswood.api.me.components
  (:require
    [com.repldriven.queenswood.api.me.coercion :as coercion]
    [com.repldriven.queenswood.api.me.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry]]))

(def UserId (schema/id-schema "UserId" "usr" examples/UserId))

(def MembershipId (schema/id-schema "MembershipId" "mem" examples/MembershipId))

(def IdentityProvider
  (coercion/identity-provider-enum-schema {:json-schema/example "google"}))

(def UserStatus
  (coercion/user-status-enum-schema {:json-schema/example "active"}))

(def Role (coercion/role-enum-schema {:json-schema/example "owner"}))

(def Me
  [:map
   {:json-schema/example examples/Me
    :description
    "The signed-in person: their user record, and whether they are an
    operator. Their memberships are at `/v1/me/memberships`."}
   [:user-id [:ref "UserId"]]
   [:issuer string?]
   [:sub string?]
   [:email string?]
   [:name [:ref "Name"]]
   [:avatar-url {:optional true} string?]
   [:identity-provider [:ref "IdentityProvider"]]
   [:status [:ref "UserStatus"]]
   [:operator boolean?]
   [:created-at [:ref "Timestamp"]]
   [:updated-at [:ref "Timestamp"]]])

(def registry
  (components-registry [#'UserId #'MembershipId #'IdentityProvider #'UserStatus
                        #'Role #'Me]))
