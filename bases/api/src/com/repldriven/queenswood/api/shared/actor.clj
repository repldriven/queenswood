(ns com.repldriven.queenswood.api.shared.actor
  "Who a request acts as, in the shape a record's `*_by` field holds.")

(defn actor
  "The caller as an actor: an operator when it carries `:admin`, the
  bank when it called with the bank's credential, otherwise a member."
  [{:keys [roles principal-type principal-id]}]
  {:kind (cond (contains? roles :admin)
               :actor-kind-operator

               (= :service principal-type)
               :actor-kind-bank

               :else
               :actor-kind-member)
   :principal-id principal-id})
