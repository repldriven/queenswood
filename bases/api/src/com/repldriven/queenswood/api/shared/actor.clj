(ns com.repldriven.queenswood.api.shared.actor
  "Who a request acts as, in the shape a record's `*_by` field holds, and
  the act it asks a synchronous write for.")

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

(defn act
  "The options a synchronous write takes for the act `request` asks for:
  the caller as `:actor`, the request's id, and `:audited? true`, so the
  write records the act in the bank's audit log."
  [request]
  {:actor (actor (:auth request))
   :request-id (:request-id request)
   :audited? true})
