(ns com.repldriven.queenswood.idv.interface
  "Identity verification (IDV) records and lifecycle. A person party
  entering pending gets an IDV, which waits for the tenant to open a
  verification session; opening one publishes a `submit-idv-check`
  command to the IDV-provider adapter, whose `idv-session-opened` event
  makes the session ready with a hand-off for the person. The adapter
  reports what the provider established as `idv-evidence-received`, and each
  report is merged into the IDV and decided against the bank's
  policies: rejected, failed, in review, accepted, or still pending.
  In-review is non-terminal, so an acceptance or a rejection may still
  follow. The owning party stays pending until the IDV accepts or
  rejects; the IDV record is the source of truth for why.
  A bank's criteria are denies on `idv-action-accept`, checked against
  the provider's declaration, and at start-up the platform policy's
  against every provider offered.
  Registers IDV component kinds (processor, event-processor,
  party-event-processor, criteria-check) through this brick's `system`
  namespace."
  (:require
    [com.repldriven.queenswood.idv.system]

    [com.repldriven.queenswood.idv.core :as core]
    [com.repldriven.queenswood.idv.domain :as domain]))

(defn get-idv
  "Load an IDV by composite primary key. Returns the IDV map or
  an `:idv/not-found` rejection anomaly if the record is missing.

  Args:
  - txn: an open FDB transaction or system bank map.
  - bank-id: bank owning the IDV.
  - verification-id: IDV identifier (`idv.<ulid>`)."
  [txn bank-id verification-id]
  (core/get-idv txn bank-id verification-id))

(defn open-session
  "Open a verification session for a party's pending IDV, and once it
  commits ask the provider's adapter for a hand-off. Returns the
  session, opening, or an anomaly: `:idv/not-found` when the party has
  no IDV in the bank, `:idv/invalid-status` unless the IDV is pending,
  `:idv/unsupported-channel` and `:idv/missing-email` against the
  provider declaration, and the policy refusals of `idv-action-submit`
  and its daily limit.

  Args:
  - config: the processor's config — `:record-db`, `:record-store`,
    `:idv-providers`, whose default provider's declaration the session
    is checked against and whose command channel reaches its adapter,
    and `:bus` and `:schemas`.
  - data: `{:bank-id :party-id :channel :return-url :email :actor}`,
    the channel `\"web\"` or `\"mobile\"` and the actor who opens it."
  [config data]
  (core/open-session config data))

(defn unmet-criteria
  "Return the verifications and screenings `policies` require of a
  person that `declaration` does not establish, as a vector of
  `:idv-verification-*` and `:idv-screening-*` keywords, empty when the
  provider meets them. A criterion is required when a deny refuses
  `idv-action-accept` while it is outstanding, so `policies` must be
  every policy in effect, the platform tier's included.

  Args:
  - policies: collection of policy maps.
  - declaration: the provider declaration, `{:verifies [...] :screens
    [...]}` naming each criterion without its prefix (`\"address\"`,
    `\"pep\"`)."
  [policies declaration]
  (domain/unmet-criteria policies declaration))

(defn check-criteria
  "Return nil when `declaration` meets what `policies` require, or an
  `:idv/unsupported-criteria` rejection whose `:unmet` names what it
  does not. Arguments as `unmet-criteria`."
  [policies declaration]
  (domain/check-criteria policies declaration))
