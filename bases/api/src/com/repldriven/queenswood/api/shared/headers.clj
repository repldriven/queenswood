(ns com.repldriven.queenswood.api.shared.headers
  "Response headers several routes document the same way.")

(defn location
  "The `Location` header a 201 documents, naming `resource`, the thing
  the request created."
  [resource]
  {:description (str "The URI of the created " resource ".")
   :schema {:type "string" :format "uri-reference"}})
