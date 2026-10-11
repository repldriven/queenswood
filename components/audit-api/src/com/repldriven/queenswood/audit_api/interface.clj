(ns com.repldriven.queenswood.audit-api.interface
  "The bank's audit log as the banking API publishes it: the malli
  components an audit entry and its list are built from, the enum
  schemas that coerce an act's kind and its subject's kind between wire
  strings and internal keywords, the example they carry, and the
  query a page of the log takes."
  (:require
    [com.repldriven.queenswood.audit-api.components :as components]))

(def
  ^{:doc
    "Malli registry of the audit schemas, keyed by the name each appears
  under in the document's `components/schemas`: `AuditEntry`,
  `AuditEntryId`, `AuditKind`, `AuditEntryList` and
  `AuditSubjectKind`. Merged into the coercion registry in `api.clj`, so
  `[:ref \"X\"]` resolves them on any route."}
  registry
  components/registry)

(def
  ^{:doc
    "Malli `:query` schema of the audit log: an optional `page`, an
  optional `kind`, the kinds of act to list, sent comma-separated and
  decoded to a vector of `:audit-kind-*` keywords, and an optional
  `performed-by-kind`, the kinds of performer to list, decoded the same
  way to `:actor-kind-*` keywords, and an optional `from` and `to`, RFC
  3339 timestamps decoded to epoch milliseconds, `from` before `to`."}
  audit-log-query
  components/AuditEntryQuery)
