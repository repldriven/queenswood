(ns com.repldriven.queenswood.access-log-api.components
  (:require
    [com.repldriven.queenswood.access-log-api.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry list-schema]]))

(def AccessEntryId
  (schema/id-schema "AccessEntryId" "axs" examples/AccessEntryId))

(def AccessEntry
  [:map
   {:json-schema/example examples/AccessEntry
    :description
    "One call to the API for the bank, by a caller authenticated for it:
    who called, which route with which ids, what it answered and how long
    it took, with no body or query value. `idempotency-key` and
    `correlation-id` are the client's own, cut to 255 characters, the
    correlation id falling back to the idempotency key. `duration` is in
    microseconds. `replayed` is true where the response came from the
    idempotency cache rather than running the call again."}
   [:access-entry-id [:ref "AccessEntryId"]]
   [:bank-id [:ref "BankId"]]
   [:request-id string?]
   [:idempotency-key {:optional true} string?]
   [:correlation-id {:optional true} string?]
   [:trace-id {:optional true} string?]
   [:requested-at [:ref "Timestamp"]]
   [:requested-by [:ref "Actor"]]
   [:method string?]
   [:route string?]
   [:path-ids [:map-of :keyword string?]]
   [:http-status int?]
   [:problem-type {:optional true} string?]
   [:replayed boolean?]
   [:client-address {:optional true} string?]
   [:duration int?]])

(def AccessEntryList (list-schema "AccessEntry" examples/AccessEntryList))

(def AccessLogQuery
  "The access log's query: a page, and optionally a reference, the
  request id, idempotency key or correlation id a call carried."
  [:map {:closed true}
   [:page {:optional true} [:ref "PageQuery"]]
   [:ref {:optional true} [:string {:min 1 :max 255}]]])

(def registry
  (components-registry [#'AccessEntry #'AccessEntryId #'AccessEntryList]))
