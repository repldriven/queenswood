(ns com.repldriven.queenswood.api.shared.actor
  "Who a request acts as, in the shape a record's `*_by` field holds.")

(defn actor
  "The caller as an actor: an operator when it carries `:admin`,
  otherwise a member."
  [{:keys [roles principal-id]}]
  {:kind (if (contains? roles :admin) :actor-kind-operator :actor-kind-member)
   :principal-id principal-id})
