(ns com.repldriven.queenswood.access-log.interface
  "The bank's access log: one entry for each call to the API by a caller
  authenticated for a bank, written once the response is chosen and off
  the request's thread, many to a transaction, appended under the bank
  and keyed by the commit's versionstamp. Each entry is also found by
  its references, the request id, the idempotency key and the
  correlation id the call carried. Entries expire: `delete-before` is
  the only deletion. Entries a full queue refuses are dropped and
  counted, so the log is complete while that count stays at zero."
  (:require
    [com.repldriven.queenswood.access-log.system]

    [com.repldriven.queenswood.access-log.core :as core]
    [com.repldriven.queenswood.access-log.writer :as writer]))

(defn record
  "Queues `entry` for writing and returns nil at once, dropping and
  counting it when the queue is full. Never throws.

  Args:
  - writer: the started `access-log/writer` component.
  - entry: `{:bank-id :request-id :idempotency-key :correlation-id
    :trace-id :requested-at :requested-by :method :route :operation-id
    :tag :path-ids :http-status :problem-type :replayed :client-address
    :duration}`, `:requested-at` when the call arrived, `:requested-by`
    an actor, `:tag` the operation's OpenAPI tag, `:path-ids` the route's path parameters and `:duration` in
    microseconds. The idempotency key and correlation id are cut to 255
    characters."
  [writer entry]
  (core/record writer entry))

(defn dropped
  "The entries `writer` has dropped since it started, refused by its
  full queue or lost to a failed write."
  [writer]
  (writer/dropped writer))

(defn list-by-bank
  "A page of `bank-id`'s access log, newest first: `{:access-entries
  :before :after}`, each entry carrying its `:access-entry-id`, `axs.`
  and its versionstamp in Crockford base32, which is also the cursor
  `:before` and `:after` give. Returns an anomaly on failure.

  Args:
  - txn: a Txn or `{:record-db :record-store}` config.
  - bank-id: the bank.
  - opts: `{:limit :after :before :ref :tags :requested-by :from :to}`,
    the cursors access entry ids, `:ref` a request id, idempotency key
    or correlation id, which narrows the page to the calls that carried
    it, `:tags` the OpenAPI tags whose operations to keep, every one when
    empty, `:requested-by` the principal ids whose calls to keep, every
    caller when empty, and `:from` and `:to` epoch milliseconds, the
    calls requested from `:from` and before `:to`. A page across several
    tags or callers reads one range for each and merges them, a read per
    range; with `:ref`, the tags and callers filter the calls that
    carried it, and with `:requested-by`, the tags filter the callers'
    calls, so such a page may hold fewer than `:limit`. A date range is
    found by a binary search of the log by each entry's `:requested-at`,
    in the order entries were written, so a long call or one whose clock
    ran behind may fall just inside or outside an edge."
  [txn bank-id opts]
  (core/list-by-bank txn bank-id opts))

(defn delete-before
  "Deletes up to `limit` of `bank-id`'s oldest access entries requested
  before `before`, with their references, tags and callers, in one
  transaction. Returns
  how many it deleted, fewer than `limit` once none older remain, or an
  anomaly.

  Args:
  - txn: a Txn or `{:record-db :record-store}` config.
  - bank-id: the bank.
  - before: epoch milliseconds; an entry requested at or after it stays.
  - limit: the most entries to delete."
  [txn bank-id before limit]
  (core/delete-before txn bank-id before limit))
