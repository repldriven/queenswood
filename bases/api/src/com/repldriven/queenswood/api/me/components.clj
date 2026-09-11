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

(def User
  [:map {:json-schema/example examples/User}
   [:user-id [:ref "UserId"]]
   [:issuer string?]
   [:sub string?]
   [:email string?]
   [:name [:ref "Name"]]
   [:avatar-url {:optional true} string?]
   [:identity-provider [:ref "IdentityProvider"]]
   [:status [:ref "UserStatus"]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at [:ref "Timestamp"]]])

(def Membership
  [:map {:json-schema/example examples/Membership}
   [:membership-id [:ref "MembershipId"]]
   [:user-id [:ref "UserId"]]
   [:bank-id [:ref "BankId"]]
   ;; Optional because the field is enriched by the handler from a
   ;; sibling brick — a stripped-down read path (or a future caller)
   ;; could legitimately omit it.
   [:bank-name {:optional true} [:ref "Name"]]
   [:role [:ref "Role"]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at [:ref "Timestamp"]]])

(def Me
  [:map {:json-schema/example examples/Me}
   [:user [:ref "User"]]
   [:memberships [:vector [:ref "Membership"]]]])

(def registry
  (components-registry [#'UserId #'MembershipId #'IdentityProvider #'UserStatus
                        #'Role #'User #'Membership #'Me]))
