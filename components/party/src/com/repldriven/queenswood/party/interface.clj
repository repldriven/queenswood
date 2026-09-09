(ns com.repldriven.queenswood.party.interface
  "Party write side: create parties (people, organisations, internal
  entities) and their linked data (person identification, national
  identifier), and drive their lifecycle — suspend, resume, close,
  merge. Person parties start pending and transition to active when an
  IDV result reaches this brick as an `idv-status-changed` event;
  `seed-active-party` is an admin/test shortcut that bypasses it.

  Suspend and resume are the reversible pause; close is terminal. They
  are a separate axis from the pending → active → rejected path IDV
  drives.

  Reads live in `bank-party-query`; this brick reuses them inside its
  own transactions. `bank-api` requires the query brick, not this one —
  party creation and lifecycle transitions reach the processor as
  commands over the bus."
  (:require
    [com.repldriven.queenswood.party.system]

    [com.repldriven.queenswood.party.core :as core]
    [com.repldriven.queenswood.party.domain :as domain]
    [com.repldriven.queenswood.party.store :as store]

    [com.repldriven.queenswood.party-query.interface :as q]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

(defn new-party
  "Create a party. Person parties also persist a person-
  identification and optional national-identifier; internal and
  organisation parties skip both. Capability is checked against
  effective policies before the write.

  A create-party command stamps its `:idempotency-key` onto the party,
  unique per bank, so a retry under that key reads the original party
  back instead of creating a second one. In-process callers pass no
  key and so write no index entry.

  Args:
  - txn: FDB handle or open transaction.
  - data: party submission map (bank-id, type,
    display-name, optional person fields, and on the command path the
    envelope's `:idempotency-key`).
  - opts: optional map; `:policies` overrides policy resolution.

  Returns the party map or an anomaly."
  ([txn data]
   (new-party txn data {}))
  ([txn data opts]
   (let-nom> [pb (core/new-party txn data opts)]
     (schema/pb->Party pb))))

(defn suspend-party
  "Suspend an active party. Direct, single-phase flip, no reactive
  leg. Capability is checked against effective policies before the
  write. Returns the suspended party or an anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - data: map with `:bank-id` and `:party-id`.
  - opts (optional): map; `:policies` overrides policy resolution
    for the capability check."
  ([txn data]
   (suspend-party txn data {}))
  ([txn data opts]
   (let-nom> [pb (core/suspend-party txn data opts)]
     (schema/pb->Party pb))))

(defn resume-party
  "Resume a suspended party, returning it to active. The inverse of
  `suspend-party`, and not a way back from `:party-status-closed` —
  close is terminal. Direct, single-phase flip, no reactive leg.
  Returns the active party or an anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - data: map with `:bank-id` and `:party-id`.
  - opts (optional): map; `:policies` overrides policy resolution
    for the capability check."
  ([txn data]
   (resume-party txn data {}))
  ([txn data opts]
   (let-nom> [pb (core/resume-party txn data opts)]
     (schema/pb->Party pb))))

(defn close-party
  "Close an active or suspended party. Terminal — there is no
  transition out of `:party-status-closed`. Refused while the party
  still holds a cash account that is not closed. Direct, single-phase
  flip, no reactive leg. Returns the closed party or an anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - data: map with `:bank-id` and `:party-id`.
  - opts (optional): map; `:policies` overrides policy resolution
    for the capability check."
  ([txn data]
   (close-party txn data {}))
  ([txn data opts]
   (let-nom> [pb (core/close-party txn data opts)]
     (schema/pb->Party pb))))

(defn merge-party
  "Merge a suspended party into an active survivor. Direct,
  single-phase flip, no reactive leg — the merged-away party's
  status flips to `:party-status-merged` and records
  `:merged-into-party-id`. IDV/KYC and other party-linked records
  stay on the original party-id; cross-domain re-pointing (e.g.
  cash accounts) is a separate reaction (ADR-0008), not orchestrated
  here. Returns the updated party or an anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - data: map with `:bank-id`, `:party-id` (the merged-away party)
    and `:into-party-id` (the survivor).
  - opts (optional): map; `:policies` overrides policy resolution
    for the capability check."
  ([txn data]
   (merge-party txn data {}))
  ([txn data opts]
   (let-nom> [pb (core/merge-party txn data opts)]
     (schema/pb->Party pb))))

(defn seed-active-party
  "Activate a pending party by writing the status transition
  directly, bypassing the IDV → changelog → event path that
  activates parties in production. Test/admin shim retained while
  harnesses can't drive a real IDV flow.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: bank id.
  - party-id: party id to activate.

  Returns the active party (pb record) or an anomaly."
  [txn bank-id party-id]
  (let-nom>
    [party (q/get-party txn bank-id party-id)
     activated (domain/activate-party party)
     saved (store/save-party txn
                             activated
                             {:bank-id bank-id
                              :party-id party-id
                              :status-before (:status party)
                              :status-after (:status activated)})]
    saved))
