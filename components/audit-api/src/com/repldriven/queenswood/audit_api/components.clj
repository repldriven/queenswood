(ns com.repldriven.queenswood.audit-api.components
  (:require
    [com.repldriven.queenswood.audit-api.coercion :as coercion]
    [com.repldriven.queenswood.audit-api.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry list-schema]]

    [clojure.string :as str]))

(def AuditEntryId (schema/id-schema "AuditEntryId" "aud" examples/AuditEntryId))

(def AuditKind
  (coercion/audit-kind-enum-schema {:json-schema/example
                                    "member-role-changed"}))

(def AuditSubjectKind
  (coercion/audit-subject-kind-enum-schema {:json-schema/example "member"}))

(def AuditEntry
  [:map
   {:json-schema/example examples/AuditEntry
    :description
    "One act in the bank's audit log: what was done, to which record,
    who did it, when, and the request that asked for it. `changeset`
    holds only the record's fields the act changed, as they were in
    `before` and as the act left them in `after`, a create having no
    `before`, and never a person's name or contact details or a
    credential. It is kept for audit: its fields and values are the
    record's as stored, and change as the record does."}
   [:audit-entry-id [:ref "AuditEntryId"]]
   [:bank-id [:ref "BankId"]]
   [:kind [:ref "AuditKind"]]
   [:subject-kind [:ref "AuditSubjectKind"]]
   [:subject-id string?]
   [:performed-at [:ref "Timestamp"]]
   [:performed-by [:ref "Actor"]]
   [:request-id {:optional true} string?]
   [:changeset {:optional true} [:ref "AuditChangeset"]]])

(def AuditChangeset
  [:map
   {:json-schema/example (:changeset examples/AuditEntry)
    :description
    "The fields of its subject an act changed: `before` as they were,
    absent on a create, and `after` as the act left them."}
   [:before {:optional true} [:map-of :keyword any?]]
   [:after {:optional true} [:map-of :keyword any?]]])

(def AuditEntryList (list-schema "AuditEntry" examples/AuditEntryList))

(defn- comma-separated
  [v]
  (if (string? v) (str/split v #",") v))

(def AuditEntryQuery
  "The audit log's query: a page, and optionally the kinds of act and
  the kinds of performer to list, each comma-separated, the request
  whose acts to list, and the instants the entries were performed from
  and before."
  [:and
   [:map {:closed true}
    [:page {:optional true} [:ref "PageQuery"]]
    [:kind {:optional true}
     [:vector {:decode/api comma-separated} [:ref "AuditKind"]]]
    [:performed-by-kind {:optional true}
     [:vector {:decode/api comma-separated} [:ref "ActorKind"]]]
    [:request-id {:optional true} [:string {:min 1 :max 64}]]
    [:from {:optional true} schema/instant-query-schema]
    [:to {:optional true} schema/instant-query-schema]]
   schema/instant-range-schema])

(def registry
  (components-registry [#'AuditChangeset #'AuditEntry #'AuditEntryId #'AuditKind
                        #'AuditEntryList #'AuditSubjectKind]))
