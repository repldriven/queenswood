(ns com.repldriven.queenswood.me-api.interface
  "The signed-in person as the banking API publishes them: the malli
  components of `/v1/me` and its memberships, and the example ids
  and membership the access examples are built from."
  (:require
    [com.repldriven.queenswood.me-api.components :as components]
    [com.repldriven.queenswood.me-api.examples :as examples]))

;; ---
;; schemas
;; ---

(def
  ^{:doc
    "Malli registry of the `/v1/me` schemas, keyed by the name each
  appears under in the document's `components/schemas`: `UserId`,
  `MembershipId`, `IdentityProvider`, `UserStatus`, `Role`, `Me`.
  Merged into the coercion registry in `api.clj`, so `[:ref \"X\"]`
  resolves them on any route."}
  registry
  components/registry)

;; ---
;; examples
;; ---

(def ^{:doc "The bank id the `/v1/me` and access examples share."} BankId
  examples/BankId)

(def
  ^{:doc
    "An owner's membership of the bank they created, as `/v1/me` lists
  it and as the access examples embed it."}
  Membership
  examples/Membership)

(def ^{:doc "The user id the `/v1/me` and access examples share."} UserId
  examples/UserId)
