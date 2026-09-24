(ns com.repldriven.queenswood.api.shared.claims
  "OIDC-claims projection helpers shared between the auth interceptor
  (which auto-upserts a User on every authenticated user JWT) and the
  onboarding handler (which uses the same projection if it ends up
  doing its own upsert as a defensive layer)."
  (:require
    [com.repldriven.mono.utility.interface :as util]))

(defn claims->identity-provider
  "Map an OIDC `identity_provider` claim (set by Keycloak when the
  user came through a federation broker) to the proto enum. When the
  claim is absent the user authenticated against Keycloak directly
  with a username/password, so default to
  `:identity-provider-password`. We deliberately avoid
  `:identity-provider-unknown` — that's the proto enum's zero value
  and protojure's encoder skips zero-valued fields on the wire,
  leading to `parseFrom` rejecting the message for missing the
  required `identity_provider` field."
  [claims]
  (case (:identity_provider claims)
    "google" :identity-provider-google
    "github" :identity-provider-github
    :identity-provider-password))

(defn claims->user-claims
  "Project OIDC JWT claims into the shape `bank-user/upsert-by-sub`
  expects. Pulls `name` from the `name` claim, falling back to a
  `given_name + family_name` concatenation when only the parts are
  present (some IdPs populate only one or the other).

  `:avatar-url` is assoc'd only when the `picture` claim is present.
  It maps to `optional string avatar_url` on the User proto, and
  protojure rejects an explicit `nil` for that field (`Invalid
  input`) where an absent key encodes fine — so a username/password
  sign-in, which carries no `picture`, would otherwise fail the
  upsert."
  [claims]
  (util/assoc-some
   {:issuer (:iss claims)
    :sub (:sub claims)
    :email (:email claims)
    :name (or (:name claims)
              (let [g (:given_name claims)
                    f (:family_name claims)]
                (cond (and g f)
                      (str g " " f)

                      g
                      g

                      f
                      f

                      :else
                      (:preferred_username claims))))
    :identity-provider (claims->identity-provider claims)}
   :avatar-url
   (:picture claims)))
