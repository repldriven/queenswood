(ns com.repldriven.queenswood.api.payee-check.links
  "OpenAPI 3 `links` objects for payee-check responses.")

(def from-check
  "Links available on a `PayeeCheck` response."
  {"GetCheck" {:operationId "RetrievePayeeCheck"
               :parameters {"check-id" "$response.body#/check-id"}}})
