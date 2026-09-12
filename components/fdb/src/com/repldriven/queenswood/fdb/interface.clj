(ns com.repldriven.queenswood.fdb.interface
  "FoundationDB Record Layer wrapper. Exposes key-value and record
  access, cursor-paginated scans, changelog append and consumption, and
  a `transact` fn running a body inside a single FDB transaction —
  returning a value or an anomaly. Component-kinds for
  cluster-file-path, db, record-db, store, meta-store and
  keyspace-prefix register via this brick's `system` namespace."
  (:require
    [com.repldriven.queenswood.fdb.system.core]

    [com.repldriven.queenswood.fdb.changelog :as changelog]
    [com.repldriven.queenswood.fdb.check :as check]
    [com.repldriven.queenswood.fdb.counter :as counter]
    [com.repldriven.queenswood.fdb.kv :as kv]
    [com.repldriven.queenswood.fdb.merge :as merge]
    [com.repldriven.queenswood.fdb.meta-data :as meta-data]
    [com.repldriven.queenswood.fdb.record :as record]
    [com.repldriven.queenswood.fdb.scan :as scan]
    [com.repldriven.queenswood.fdb.transact :as transact]))

;; ---
;; kv
;; ---

(defn set-str
  "Sets a string value at key, in its own transaction."
  [db key value]
  (kv/set-str db key value))

(defn get-str
  "Reads the string value at key, or nil."
  [db key]
  (kv/get-str db key))

(defn set-bytes
  "Sets a byte-array value at key, in its own transaction."
  [db key value]
  (kv/set-bytes db key value))

(defn get-bytes
  "Reads the byte-array value at key, or nil."
  [db key]
  (kv/get-bytes db key))

;; ---
;; records
;; ---

(defn load-record
  "Loads a record by primary key from an open FDBRecordStore, returning
  serialized bytes or nil. Takes one primary-key part per element of a
  composite key."
  [store & primary-key-parts]
  (apply record/load store primary-key-parts))

(defn save-record
  "Persists a protobuf message into an open FDBRecordStore."
  [store record]
  (record/save store record))

(defn delete-record
  "Deletes a record by primary key from an open FDBRecordStore.
  Returns true if deleted, false if not found."
  [store & primary-key-parts]
  (apply record/delete store primary-key-parts))

;; ---
;; queries
;; ---

(defn query-records
  "Queries an open FDBRecordStore where field equals value.
  Returns a vector of serialized byte arrays. opts supports
  :index to pin the planner to a named index."
  ([store record-type field value]
   (record/query store record-type field value))
  ([store record-type field value opts]
   (record/query store record-type field value opts)))

(defn query-record
  "Queries an open FDBRecordStore where field equals value,
  capping the planner at one result. Returns the first
  matching record bytes, or nil. opts supports :index to
  pin the planner to a named index."
  ([store record-type field value]
   (record/query-one store record-type field value))
  ([store record-type field value opts]
   (record/query-one store record-type field value opts)))

(defn query-record-compound
  "Queries an open FDBRecordStore where all [field value]
  pairs match, capping the planner at one result. Returns
  the first matching record bytes, or nil. opts supports
  :index to pin the planner to a named index."
  ([store record-type filters]
   (record/query-one-compound store record-type filters))
  ([store record-type filters opts]
   (record/query-one-compound store record-type filters opts)))

(defn enum-value
  "The comparand a query on the enum `field` of `record-type` takes,
  for the enum value with `number`.

  The planner accepts an enum comparand only as a generated protobuf
  enum whose descriptor equals the field's, or as the Record Layer's
  own dynamic enum. An open store's descriptors are rebuilt from the
  meta-data it persisted, so a generated class's descriptor is a
  different object and never equal; the name and number are read off
  the store's own descriptor and handed over as the dynamic form.

  Args:
  - store: an open FDBRecordStore.
  - record-type: record type name.
  - field: the enum field's proto name.
  - number: the enum value's protobuf int."
  [store record-type field number]
  (record/enum-value store record-type field number))

(defn query-records-by-map-entry
  "Queries records where a proto map field has at least one
  entry matching `map-key`/`map-value`. Returns a vector of
  serialized byte arrays. opts supports :index to pin the
  planner to a named index."
  ([store record-type map-field map-key map-value]
   (record/query-by-map-entry store record-type map-field map-key map-value))
  ([store record-type map-field map-key map-value opts]
   (record/query-by-map-entry store
                              record-type
                              map-field
                              map-key
                              map-value
                              opts)))

;; ---
;; aggregates
;; ---

(defn count-records
  "Counts records in a COUNT index group, reading at SERIALIZABLE.
  `key` is the grouping key — a single value, or a vector for a
  compound key."
  [store index-name key]
  (record/count-records store index-name key))

(defn count-records-snapshot
  "`count-records`, read at SNAPSHOT. For a safety-net limit, where a
  count stale by the in-flight writers is acceptable."
  [store index-name key]
  (record/count-records store index-name key {:isolation :snapshot}))

(defn sum-records
  "Sums the trailing value column of a SUM index over the group
  whose grouping key is `key`. An empty group sums to 0."
  [store index-name key]
  (record/sum-records store index-name key))

(defn count-groups
  "Counts distinct grouping-key entries in a COUNT index whose
  group key starts with `prefix` — one per group, not the sum
  of per-group counts."
  [store index-name prefix]
  (record/count-groups store index-name prefix))

;; ---
;; scans
;; ---

(defn scan-records
  "Cursor-paginated scan of a store, returning `{:entries :before
  :after}`.

  `:before` and `:after` are the cursors of the page's first and last
  records, phrased in the client's display direction — so they always
  mean prev / next regardless of `:order`. `:after` is set only when
  rows remain beyond the page.

  Args:
  - store: an open FDBRecordStore.
  - opts: `{:prefix :after :before :limit :order}`. `:prefix` is a
    vector of leading PK parts scoping the scan; a cursor is the PK
    past that prefix. `:order` is `:asc` (default) or `:desc`, and
    selects the display direction — under `:desc` the first page
    returns the highest-keyed records."
  [store opts]
  (scan/scan store opts))

(defn scan-record-entries
  "As `scan-records`, but each record comes back as
  `{:key cursor :record bytes}` so a caller can pair records from two
  stores without deserialising them."
  [store opts]
  (scan/scan-entries store opts))

(defn merge-scan
  "Pairs two stores on the first key element past their prefixes and
  reduces over the pairs in key order. Takes the config rather than
  opened stores, because each page refill needs its own transaction.

  An outer join: a key in one store but not the other still reaches
  `f`, with the absent side empty. Not a consistent snapshot — the
  sides refill in separate transactions.

  Args:
  - config: map with :record-db and :record-store.
  - opts: `{:left {:store :prefix :limit} :right {...}}`.
  - f: reducing fn of `[acc {:keys [key left right]}]`, where the
    records are bytes. May return `reduced`; an anomaly propagates.
  - init: initial accumulator."
  [config opts f init]
  (merge/merge-scan config opts f init))

;; ---
;; changelog
;; ---

(defn write-changelog
  "Writes a versionstamped changelog entry for record-id and bumps
  store-name's sentinel. The entry carries `(record-id,
  changelog-bytes)`, so a consumer gets the transition without
  re-loading the entity. Takes the Txn rather than an opened store so
  the keyspace prefix travels with it."
  [txn store-name record-id changelog-bytes]
  (changelog/write (transact/open txn store-name)
                   (:prefix txn)
                   store-name
                   record-id
                   changelog-bytes))

(defn process-changelog
  "Reads store-name's changelog forward from consumer-id's checkpoint,
  calling `(handler ctx changelog-bytes)` per entry and advancing the
  checkpoint — all in one transaction. Each consumer tracks its own
  checkpoint, so a fresh consumer-id starts from the beginning of the
  log.

  Args:
  - opts: `:deduplicate?` (default true) processes only the latest
    entry per record-id; set false for an audit consumer needing every
    write. `:keyspace-prefix` scopes the changelog and checkpoint keys,
    and must match the writing system's or this consumer reads an empty
    log."
  ([record-db consumer-id store-name handler]
   (changelog/process record-db consumer-id store-name handler))
  ([record-db consumer-id store-name handler opts]
   (changelog/process record-db consumer-id store-name handler opts)))

;; ---
;; counters
;; ---

(defn allocate-counter
  "Atomically increments the counter at key-parts within store-name,
  returning the post-increment value. Counter keys sit at the FDB root,
  so the keyspace prefix scopes them as it does records and the
  changelog. Takes the Txn for the same reason as `write-changelog`."
  [txn store-name & key-parts]
  (apply counter/allocate
         (transact/open txn store-name)
         (:prefix txn)
         key-parts))

;; ---
;; transactions
;; ---

(defn transact
  "Runs f within a transaction. f receives a Txn. Given an existing
  Txn, reuses it; given a config map with :record-db and
  :record-store, opens a fresh FDB transaction, and an optional
  :keyspace-prefix on that map scopes every key it writes.

  If f returns an anomaly the transaction is rolled back and the
  anomaly returned to the caller."
  ([txn-or-config f]
   (transact/transact txn-or-config f))
  ([txn-or-config f category message]
   (transact/transact txn-or-config f category message)))

(defn open
  "Opens a named store within the transaction. Memoised for the life of
  the txn, so the same store name returns the same FDBRecordStore."
  [txn store-name]
  (transact/open txn store-name))

(defn ctx->txn
  "Adapts a raw FDB context into a Txn so store fns can be
  called from within a handler that owns its own ctx
  (e.g. a changelog relay handler). open-store-fn takes
  [ctx store-name] and returns an opened FDBRecordStore;
  opens are memoised for the life of the Txn. The keyspace prefix
  comes off open-store-fn's metadata, so a handler that owns its own
  ctx still writes into its system's keyspace."
  [ctx open-store-fn]
  (let [cache (atom {})]
    (transact/->Txn (fn [store-name]
                      (or (get @cache store-name)
                          (let [s (open-store-fn ctx store-name)]
                            (swap! cache assoc store-name s)
                            s)))
                    (:keyspace-prefix (meta open-store-fn)))))

;; ---
;; meta-data
;; ---

(defn build-meta-data
  "Builds Record Layer meta-data from a descriptor — the name of a
  generated outer class, or a `Descriptors$FileDescriptor` — and a
  declaration in the shape of `fdb-record-types.yml`: a `version`, and
  under `stores` each record store's record type, primary key, indexes
  carrying the `added` and `modified` versions the Record Layer keeps
  per index, and the `former-indexes` removed from it. Returns the
  meta-data, or an anomaly listing each field missing or malformed."
  [descriptor declaration]
  (meta-data/build descriptor declaration))

(defn describe-meta-data
  "The version, and every index and former index with its versions and
  key, as a map — what a log line reports and a test compares."
  [meta-data]
  (meta-data/describe meta-data))

(defn validate-meta-data-evolution
  "Nil when `new` may replace `old` in a store holding data, under the
  rules `save-meta-data` applies: a higher version, no field removed, an
  index whose key changed carrying a higher `modified`, a removed index
  listed as former. Otherwise an anomaly carrying the Record Layer's
  reason and the index it names."
  [old new]
  (meta-data/validate-evolution old new))

(defn load-meta-data
  "The meta-data persisted at path, nil where none is, or an anomaly."
  [record-db path]
  (meta-data/load record-db path))

(defn save-meta-data
  "Persists meta-data at path, returning `:saved`, or `:current` when
  the stored meta-data is at the same version and identical. Returns an
  anomaly when the stored version is higher, or equal with any change,
  or lower with a change the evolution rules refuse."
  [record-db path meta-data]
  (meta-data/save record-db path meta-data))

;; ---
;; checks
;; ---

(def
  ^{:doc
    "True when x is a Txn, and so can be threaded into a
  nested `transact` rather than opening a new transaction."}
  txn?
  check/txn?)

(def
  ^{:doc
    "True when the throwable is FDB's unique-index violation,
  letting a caller turn a contended insert into a domain rejection."}
  uniqueness-violation?
  check/uniqueness-violation?)
