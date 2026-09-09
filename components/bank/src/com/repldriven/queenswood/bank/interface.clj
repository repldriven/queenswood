(ns com.repldriven.queenswood.bank.interface
  "Bank write side: provisions a bank with a service-account client,
  an organization party, a default chart of bank-owned ledger accounts
  per currency, tier-specific policy bindings, and (onboarding) the
  owner membership — all in one transaction.

  Reads live in `bank-bank-query`; `bank-api` requires the query
  brick, not this one — bank creation reaches the processor as a
  command over the bus."
  (:require
    [com.repldriven.queenswood.bank.system]

    [com.repldriven.queenswood.bank.core :as core]))

(defn new-bank
  "Provision a new bank with a service-account client, an organization
  party, and a default chart of ledger accounts per currency, and bind
  the tier policies to it. When `:membership` is supplied, also create
  the owner membership in the same transaction. Returns
  `{:bank {…} :membership <map-or-nil>}` or an anomaly. The
  service-account secret is not returned — callers needing one mint it
  via `identity-provider/rotate-secret` after creation.

  Args:
  - txn: FDB transaction or db handle.
  - bank-name: bank display name.
  - bank-status: `:bank-status-*` keyword.
  - tier: tier name (string, required) selecting `tier=<name>`-labelled
    policies to bind to the new bank — creation is rejected
    `:bank/unknown-tier` when it is nil or resolves to no policies.
  - currencies: collection of ISO 4217 currency strings.
  - opts: map; `:identity-provider` (required) is the IDP component
    that issues the bank's service-account client — without one a bank
    has no credentials, so creation is rejected `:bank/missing-identity-provider`;
    `:audience` (string) is the `aud` claim stamped on tokens minted
    for the new client; `:company-binding` (map, optional) is the
    confirmed legal-entity snapshot to bind the bank to (onboarding) —
    creation is rejected `:onboarding/company-not-active` unless its
    `:company-status` is active; `:membership` (map, optional) is
    `{:user-id … :role …}` for the owner membership — rejected
    `:membership/already-exists` when the user already belongs to a
    bank; `:policies` overrides the platform policies used for the
    capability check."
  [txn bank-name bank-status tier currencies opts]
  (core/new-bank txn
                 bank-name
                 bank-status
                 tier
                 currencies
                 opts))

(defn change-tier
  "Rebind a bank onto a new tier's policies, in one transaction:
  unbinds every existing binding whose policy carries a `tier` label
  (identified from the policy, not any tier previously stored on the
  bank — so a bank with no stored tier still transitions cleanly),
  binds the new tier's policies, and persists the bank's `:tier`.
  Returns the updated bank map or an anomaly.

  Rejects `:bank/invalid-status` unless the bank is test or live, and
  `:bank/unknown-tier` when `tier` resolves to no policies (a typo
  must not silently strip all tier bindings).

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: bank id string.
  - tier: tier name (string) selecting `tier=<name>`-labelled
    policies to bind."
  [txn bank-id tier]
  (core/change-tier txn bank-id tier))

(defn change-status
  "Flip a bank between `:bank-status-test` and `:bank-status-live` in
  one transaction: swaps the service-account client's audience via
  the identity-provider (before persisting, so an IDP failure aborts
  cleanly) and persists the new `:status` with a changelog
  entry. Returns the updated bank map or an anomaly.

  Rejects `:bank/invalid-status` unless the bank is currently test or
  live, or when `new-status` matches the bank's current status.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: bank id string.
  - new-status: `:bank-status-test` or `:bank-status-live`.
  - opts: map; `:identity-provider` (required) is the IDP component
    used to update the client's audience; `:audience` (string) is the
    `aud` claim to stamp on tokens for the target status — bank-api
    resolves this from its status→audience config, same as the
    `create-bank` pattern."
  [txn bank-id new-status opts]
  (core/change-status txn bank-id new-status opts))
