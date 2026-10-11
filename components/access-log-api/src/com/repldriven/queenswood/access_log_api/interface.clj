(ns com.repldriven.queenswood.access-log-api.interface
  "The bank's access log as the banking API publishes it: the malli
  components an access entry and its list are built from, the example
  they carry, and the query a page of the log takes."
  (:require
    [com.repldriven.queenswood.access-log-api.components :as components]))

(def
  ^{:doc
    "Malli registry of the access log schemas, keyed by the name each
  appears under in the document's `components/schemas`: `AccessEntry`,
  `AccessEntryId` and `AccessEntryList`. Merged into the coercion
  registry in `api.clj`, so `[:ref \"X\"]` resolves them on any route."}
  registry
  components/registry)

(def
  ^{:doc
    "Malli `:query` schema of the access log: an optional `page`, an
  optional `ref`, a request id, idempotency key or correlation id, which
  narrows the log to the calls that carried it, and an optional `tag`,
  the OpenAPI tags whose operations to list, and an optional
  `requested-by`, the principal ids whose calls to list, each sent
  comma-separated and decoded to a vector of strings, and an optional
  `from` and `to`, RFC
  3339 timestamps decoded to epoch milliseconds, `from` before `to`."}
  access-log-query
  components/AccessLogQuery)
