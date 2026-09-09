(ns com.repldriven.queenswood.party-query.interface
  "Read-side (query) surface for parties: load one, list a bank's
  parties, enrich with sub-records, and compare names. This is the only
  party brick `bank-api` (and other readers) may require — it exposes no
  writes. Party creation and status transitions live in `bank-party`
  (commands + watcher), which reuses these reads inside its own
  transactions.

  `find-party-by-idempotency-key` is a read primitive for the write
  sibling's create-party idempotency read-back; the public reads for
  API consumers are `get-party`, `get-party-detail` and
  `get-parties`."
  (:require
    [com.repldriven.queenswood.party-query.core :as core]
    [com.repldriven.queenswood.party-query.domain :as domain]
    [com.repldriven.queenswood.party-query.store :as store]))

(defn get-party
  "Load a party by bank and id.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: bank id.
  - party-id: party id.

  Returns the party map or a `:party/not-found` anomaly."
  [txn bank-id party-id]
  (core/get-party txn bank-id party-id))

(defn find-party-by-idempotency-key
  "Return the party previously written under `idempotency-key`, or
  nil. A read primitive for the write sibling's new-party idempotency
  read-back — the create-party command is the only path that stamps a
  key, so a party created in-process carries none and is never found
  here.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: bank id.
  - idempotency-key: the command's idempotency key."
  [txn bank-id idempotency-key]
  (store/find-party-by-idempotency-key txn bank-id idempotency-key))

(defn get-party-detail
  "Load a party, optionally enriched with sub-records selected by the
  `embed` opts.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: bank id.
  - party-id: party id.
  - opts: embed flags — `:person-identification` (given/middle/family
    names, date-of-birth, nationality), `:address`, and
    `:national-identifier`. Each truthy flag opts that sub-record into
    the result; omit them all for just the summary.

  Returns the (possibly enriched) party map, or a `:party/not-found`
  anomaly. Enrichment is best-effort — a sub-record read failure is
  skipped, never propagated. Internal and organisation parties carry no
  person identification, so those embeds are no-ops for them."
  [txn bank-id party-id opts]
  (core/get-party-detail txn bank-id party-id opts))

(defn get-parties
  "List parties for a bank in a paged result.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: bank id.
  - opts: optional map with :after, :before, :limit, :order.

  Returns `{:parties [...] :before id|nil :after id|nil}` or an
  anomaly."
  ([txn bank-id]
   (store/get-parties txn bank-id))
  ([txn bank-id opts]
   (store/get-parties txn bank-id opts)))

(defn match-name
  "Compare a stored party name against a query name, returning a
  match grade.

  Args:
  - party-name: the stored display name.
  - query-name: the name to compare against.

  Returns `:match`, `:close-match`, or `:no-match`."
  [party-name query-name]
  (domain/match-name party-name query-name))
