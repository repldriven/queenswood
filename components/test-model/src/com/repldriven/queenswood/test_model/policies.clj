(ns com.repldriven.queenswood.test-model.policies)

(defn- no-worse?
  [pre post]
  (>= post pre))

(defn permits-available?
  [{:keys [min improving?] :as _rule} pre post]
  (or (>= post min)
      (and improving?
           (< pre min)
           (no-worse? pre post))))

(defn permits?
  [policies kind pre post]
  (case kind
    :available (let [rule (:available policies)]
                 (or (nil? rule)
                     (permits-available? rule pre post)))
    true))

(defn- variant
  [m]
  (first (keys m)))

(defn- available-filter?
  [f]
  (= "available" (get-in f [:kind :computed :name])))

(defn available-rule
  [policies]
  (some (fn [limit]
          (let [bound (get-in limit
                              [:bound :kind :min :aggregate :kind :amount])]
            (when (and bound
                       (some available-filter?
                             (get-in limit [:kind :balance :filters])))
              {:min (get-in bound [:value :value])
               :improving? (= :limit-allow-improving (:allow limit))})))
        (mapcat :limits policies)))

(defn- held-to
  [state bank-id]
  (concat (:platform-policies state)
          (get-in state [:banks bank-id :policies])))

(defn permitted?
  [state bank-id kind action]
  (let [effects (for [policy (held-to state bank-id)
                      rule (:capabilities policy)
                      :let [arm (get-in rule [:kind kind])]
                      :when (and (= action (:action arm))
                                 (empty? (:filters arm)))]
                  (:effect rule))]
    (and (some #{:effect-allow} effects)
         (not-any? #{:effect-deny} effects))))

(defn- count-bound
  [limit]
  (get-in limit [:bound :kind :max :aggregate :kind :count]))

(defn- applies?
  [limit kind action window]
  (let [filters (get-in limit [:kind kind :filters])]
    (and (= kind (variant (:kind limit)))
         (= window (:window (count-bound limit)))
         (or (empty? filters)
             (and action (some (fn [f] (= {:action action} f)) filters))))))

(defn within-count?
  [state bank-id kind action window current]
  (every? (fn [limit]
            (or (not (applies? limit kind action window))
                (<= (inc current) (:value (count-bound limit)))))
          (mapcat :limits (held-to state bank-id))))

(defn with-policies
  [state {:keys [platform tier]}]
  (assoc state
         :platform-policies [platform]
         :tier-policy tier
         :policies {:available (available-rule [platform])}))

(def bind-policy
  {:run? (constantly false)
   :next-state
   (fn [state {[bank-id policy] :args}]
     (update-in state [:banks bank-id :policies] (fnil conj []) policy))})
