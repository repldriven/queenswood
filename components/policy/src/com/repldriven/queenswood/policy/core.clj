(ns com.repldriven.queenswood.policy.core
  (:require
    [com.repldriven.queenswood.policy.domain :as domain]
    [com.repldriven.queenswood.policy.effective :as effective]
    [com.repldriven.queenswood.policy.store :as store]

    [com.repldriven.mono.cache.interface :as cache]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn new-policy
  [config data]
  (let [policy (domain/new-policy data)
        ;; When the caller supplied a stable :policy-id (seed data),
        ;; check whether the policy already exists and preserve the
        ;; original :created-at so it doesn't shift on every bootstrap
        ;; run. A not-found anomaly means first install; we ignore it
        ;; and let the new :created-at stand.
        existing (when (:policy-id data)
                   (store/get-policy config (:policy-id policy)))
        policy (cond-> policy
                       (and (not (error/anomaly? existing))
                            (:created-at existing))
                       (assoc :created-at (:created-at existing)))]
    (let-nom> [_ (store/save-policy config policy)]
      policy)))

(defn archive-policy
  "Archive a policy: a terminal lifecycle state that removes it from
  evaluation. Rejects `:policy/still-bound` when the policy still has
  bindings. Returns the archived policy map or an anomaly."
  [config policy-id]
  (let-nom> [policy (store/get-policy config policy-id)
             bindings (store/get-bindings-for-policy config policy-id)
             archived (domain/archive policy bindings)
             _ (store/save-policy config archived)]
    archived))

(defn get-policy
  [txn policy-id]
  (store/get-policy txn policy-id))

(defn get-policies
  ([txn]
   (store/get-policies txn))
  ([txn opts]
   (store/get-policies txn opts)))

(defn new-binding
  [config data]
  ;; An archived policy can't gain new bindings. A missing policy stays
  ;; permissive (an anomaly here isn't archived), preserving the prior
  ;; behaviour where binding doesn't assert policy existence — seed and
  ;; bank-creation flows bind pre-existing, active tier policies.
  (let [policy (store/get-policy config (:policy-id data))]
    (if (and (not (error/anomaly? policy))
             (= :policy-status-archived (:status policy)))
      (error/reject :policy/archived
                    {:message "Cannot bind an archived policy"
                     :policy-id (:policy-id data)})
      (let [binding (domain/new-binding data)]
        (let-nom> [_ (store/save-binding config binding)]
          binding)))))

(defn remove-binding
  "Remove a policy binding by id. Loads it first so a missing id rejects
  `:policy-binding/not-found` (the store delete alone doesn't reject).
  Returns the removed binding map or an anomaly."
  [config binding-id]
  (let-nom> [binding (store/get-binding config binding-id)
             _ (store/delete-binding config binding-id)]
    binding))

(defn get-binding
  [txn binding-id]
  (store/get-binding txn binding-id))

(defn get-bindings
  ([txn]
   (store/get-bindings txn))
  ([txn opts]
   (store/get-bindings txn opts)))

(defn get-bindings-for-bank
  [txn bank-id]
  (store/get-bindings-for-bank txn bank-id))

(defn- bound-policies
  "Returns the policies bound (via PolicyBinding records) to the
  bank in `selectors`, or `[]` when the selector carries no bank."
  [txn selectors]
  (if-let [bank-id (:bank-id selectors)]
    (let-nom> [bindings (store/get-bindings-for-bank txn bank-id)]
      (reduce (fn [acc b]
                (let [p (store/get-policy txn (:policy-id b))]
                  (if (map? p) (conj acc p) (reduced p))))
              []
              bindings))
    []))

(defn platform-policies
  [policies]
  (filterv (fn [p] (= "platform" (get-in p [:labels "tier"]))) policies))

(defn get-effective-policies
  [txn selectors]
  (let-nom> [platform (store/get-policies-by-label txn "tier" "platform")
             bound (bound-policies txn selectors)]
    (vec (concat platform bound))))

(defn- stamped-effective-policies
  [cache txn selectors]
  (let-nom> [stamp (store/read-stamp txn)]
    (let [k [:effective-policies (:bank-id selectors)]
          entry (cache/lookup cache k (constantly nil))]
      (if (and entry (= stamp (:stamp entry)))
        (:policies entry)
        (let-nom> [policies (get-effective-policies txn selectors)]
          (cache/evict cache k)
          (cache/lookup cache k (constantly {:stamp stamp :policies policies}))
          policies)))))

(defn get-effective-policies-cached
  [txn selectors cache]
  (if cache
    (stamped-effective-policies cache txn selectors)
    (get-effective-policies txn selectors)))

(defn get-effective-policy
  [txn selectors]
  (let-nom> [policies (get-effective-policies txn selectors)]
    (effective/resolve-effective policies)))

(defn get-policies-by-tier
  [txn tier]
  (store/get-policies-by-label txn "tier" tier))

(defn get-tiers
  [txn]
  (let-nom> [{:keys [items]} (store/get-policies txn {:limit 1000})]
    (->> items
         (keep (fn [{:keys [labels description]}]
                 (when-let [tier (get labels "tier")]
                   {:tier tier :description (or description "")})))
         (reduce (fn [acc {:keys [tier] :as t}]
                   (if (some (fn [x] (= (:tier x) tier)) acc)
                     acc
                     (conj acc t)))
                 [])
         vec)))
