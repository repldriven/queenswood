(ns com.repldriven.queenswood.demo-digital-bank-api.shared.parameters)

(def idempotency-key
  "The header a submission carries so a repeated tap is answered once."
  {:name "Idempotency-Key"
   :in "header"
   :required false
   :schema {:type "string" :maxLength 64}
   :description
   "A key the app mints per submission; the same key answers the same."})
