(ns com.repldriven.queenswood.idv.interface
  "Identity verification (IDV) records and lifecycle. Persists IDV
  state per (organization, verification-id) and bridges to an
  IDV-provider adapter via the message bus: a `submit-idv-check`
  command is published on initiation, and an `idv-completed` event
  flips the record to in-review, accepted, rejected, or failed.
  In-review and failed are non-terminal — accepted/rejected may
  still follow once manual review resolves, or the provider retries
  after a technical failure. The owning party stays pending
  throughout; the IDV record is the source of truth for why.
  A bank's criteria are denies on `idv-action-accept`, checked against
  the deployment's provider declaration (`system/idv-provider.yml`).
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
