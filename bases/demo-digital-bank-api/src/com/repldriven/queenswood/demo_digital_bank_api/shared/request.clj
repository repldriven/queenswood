(ns com.repldriven.queenswood.demo-digital-bank-api.shared.request)

(defn body [request] (get-in request [:parameters :body]))

(defn client-key
  "The key the app sent with a submission, or nil."
  [request]
  (get-in request [:headers "idempotency-key"]))
