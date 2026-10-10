(ns com.repldriven.queenswood.audit.interface
  "The bank's audit log: one append-only entry for each act a person, a
  bank or an operator performs on a bank's records, committed in the
  transaction that makes the change and keyed by that commit's
  versionstamp, so entries read in commit order and nothing can
  overwrite one. An entry names the act, the record it was performed
  on, who performed it and the API request that asked for it. The
  platform's own acts are not recorded."
  (:require
    [com.repldriven.queenswood.audit.core :as core]))

(defn record
  "Appends `entry` to its bank's audit log in `txn`, and to its
  subject's history beside it. Records nothing when the entry has no
  `:created-by`, which is an act the platform performed by itself.
  Returns nil, or an anomaly.

  Args:
  - txn: the transaction that makes the change the entry records.
  - entry: `{:bank-id :kind :subject-kind :subject-id :request-id
    :details :created-by}`, `:kind` an `:audit-kind-*` and
    `:subject-kind` an `:audit-subject-kind-*` keyword, `:details` a map
    of whatever the act carried, of any depth, kept as json, so a keyword
    reads back as its name, and never a person's name or contact
    details, `:created-by` the actor,
    and `:created-at` optional, now by default."
  [txn entry]
  (core/record txn entry))

(defn list-by-bank
  "A page of `bank-id`'s audit log, newest first: `{:audit-entries
  :before :after}`, each entry carrying its `:audit-entry-id`, `aud.`
  and its versionstamp in Crockford base32, which is also the cursor
  `:before` and `:after` give. Returns an anomaly on failure.

  Args:
  - txn: a Txn or `{:record-db :record-store}` config.
  - bank-id: the bank.
  - opts: `{:limit :after :before :kinds}`, the cursors audit entry ids
    and `:kinds` the `:audit-kind-*` keywords to keep, every kind
    when empty. A page across several kinds reads each kind's entries
    and merges them, so it costs a read per kind."
  [txn bank-id opts]
  (core/list-by-bank txn bank-id opts))

(defn list-by-subject
  "As `list-by-bank`, for the acts performed on one record of the bank.

  Args:
  - txn: a Txn or `{:record-db :record-store}` config.
  - bank-id: the bank.
  - subject-id: the record's id.
  - opts: `{:limit :after :before}`."
  [txn bank-id subject-id opts]
  (core/list-by-subject txn bank-id subject-id opts))
