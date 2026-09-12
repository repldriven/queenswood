(ns com.repldriven.queenswood.user.interface
  "Platform-identity User: a human operator of Queenswood, 1:1 with
  an OIDC (issuer, sub) pair from whichever IdP is federated. Kept
  strictly separate from Party (a customer in the banking domain) --
  see docs/prd/access.md. Memberships (`bank-membership`) join Users
  to Organizations with a Role."
  (:require
    [com.repldriven.queenswood.user.core :as core]))

(defn upsert-by-sub
  "Idempotent upsert keyed by the OIDC (issuer, sub) pair. First
  call (unknown pair) creates a User from the claims; subsequent
  calls apply fresh claims (email/name/avatar may have changed) and
  refresh `updated-at`. user-id + issuer + sub + status stay put
  across re-signins.

  Args:
  - txn: FDB transaction or db handle.
  - claims: map with `:issuer` and `:sub` (both required),
    `:email`, `:name`, `:avatar-url`, `:identity-provider`
    (`:identity-provider-*` keyword; defaults to
    `:identity-provider-unknown`).

  Returns the User map or an anomaly."
  [txn claims]
  (core/upsert-by-sub txn claims))

(defn find-by-sub
  "Look up a User by the OIDC (issuer, sub) pair. Returns the User
  map or nil if absent (NOT an anomaly -- callers use the nil to
  drive first-signin onboarding).

  Args:
  - txn: FDB transaction or db handle.
  - issuer: OIDC `iss` claim (string).
  - sub: OIDC `sub` claim (string)."
  [txn issuer sub]
  (core/find-by-sub txn issuer sub))

(defn find-by-id
  "Load a User by `user-id`. Returns the User map or a
  `:user/not-found` rejection anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - user-id: user id (string)."
  [txn user-id]
  (core/find-by-id txn user-id))
