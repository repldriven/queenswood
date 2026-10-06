(ns com.repldriven.queenswood.policy.limit
  (:require
    [com.repldriven.queenswood.policy.domain :as domain]
    [com.repldriven.queenswood.policy.match :as match]

    [com.repldriven.mono.error.interface :as error]))

(defn- aggregate-kind [agg] (match/variant (:kind agg)))

(defn- aggregate-fields [agg] (get-in agg [:kind (aggregate-kind agg)]))

;; ---------------------------------------------------------------------------
;; Value shape — values come in two shapes today: a raw scalar (for `:count`)
;; and an amount map `{:currency :value}` (for `:amount`). New value shapes
;; (e.g. `:rate {:basis-points …}`, `:tier {:tier …}`) plug in via the two
;; multimethods below.

(defn- value-kind
  "Tags a value with its shape so the multimethods below can
  dispatch on it."
  [v]
  (cond
   (and (map? v) (contains? v :currency))
   :amount

   (number? v)
   :scalar

   :else
   :default))

(defmulti ^:private num-value
  "Returns the numeric magnitude of `v` regardless of shape."
  value-kind)

(defmethod num-value :default [_] nil)
(defmethod num-value :scalar [v] v)
(defmethod num-value :amount [v] (:value v))

(defmulti ^:private compatible-values?
  "True when two values are comparable beyond magnitude — e.g.
  amounts must share a currency. Dispatches on the limit-side
  value's shape. Default permits any pairing."
  (fn [limit-value _request-value] (value-kind limit-value)))

(defmethod compatible-values? :default [_ _] true)

(defmethod compatible-values? :amount
  [limit-value request-value]
  (= (:currency limit-value) (:currency request-value)))

;; ---------------------------------------------------------------------------
;; Bound shape — emit the sides to evaluate. New bound shapes register here.

(defmulti ^:private bound-sides
  "Returns a seq of `{:side :aggregate}` entries to evaluate
  against the request. `:max`/`:min` produce one entry; `:range`
  produces two; the default emits nothing (limit silently doesn't
  apply)."
  (fn [bound] (match/variant (:kind bound))))

(defmethod bound-sides :max
  [bound]
  [{:side :max :aggregate (get-in bound [:kind :max :aggregate])}])

(defmethod bound-sides :min
  [bound]
  [{:side :min :aggregate (get-in bound [:kind :min :aggregate])}])

(defmethod bound-sides :range
  [bound]
  [{:side :max :aggregate (get-in bound [:kind :range :max])}
   {:side :min :aggregate (get-in bound [:kind :range :min])}])

(defmethod bound-sides :default [_bound] [])

;; ---------------------------------------------------------------------------
;; Window match — does the request's window count as matching a limit on
;; `limit-window`? Default is equality (which covers `:time-window-instant`,
;; `:time-window-daily`, `:time-window-weekly`, `:time-window-monthly`,
;; `:time-window-rolling`); specific windows can override for sub-day,
;; rolling-with-params, or business-day shapes.

(defmulti ^:private window-matches?
  (fn [limit-window _request-window] limit-window))

(defmethod window-matches? :default
  [limit-window request-window]
  (= limit-window request-window))

;; ---------------------------------------------------------------------------
;; Aggregate match — does the limit's aggregate apply to the request? Default
;; covers kind + window match and value-shape compatibility (the latter is
;; where the per-value-kind rules — currency for `:amount`, …  — kick in).
;; New aggregate kinds register their own behaviour.

(defn- aggregate-shape-matches?
  [agg request]
  (and (= (:aggregate request) (aggregate-kind agg))
       (window-matches? (:window (aggregate-fields agg))
                        (:window request))
       (compatible-values? (:value (aggregate-fields agg))
                           (:value request))))

(defmulti ^:private aggregate-applies? (fn [agg _request] (aggregate-kind agg)))

(defmethod aggregate-applies? :default
  [agg request]
  (aggregate-shape-matches? agg request))

;; ---------------------------------------------------------------------------
;; Side semantics — out-of-bound and improving-direction. New sides register
;; their own behaviour; today only `:max` and `:min` exist.

(defmulti ^:private out-of-bounds? (fn [side _value _bound-value] side))

(defmethod out-of-bounds? :max [_ value bound-value] (> value bound-value))
(defmethod out-of-bounds? :min [_ value bound-value] (< value bound-value))

(defmulti ^:private improving? (fn [side _pre _post] side))

(defmethod improving? :max [_ pre post] (<= post pre))
(defmethod improving? :min [_ pre post] (>= post pre))

;; ---------------------------------------------------------------------------
;; Leniency — does the limit's `:allow` modifier excuse what would otherwise
;; be a violation? Default is strict (no leniency); specific allow keywords
;; register their own predicate.

(defmulti ^:private lenient? (fn [allow _side _pre _post _bound-value] allow))

(defmethod lenient? :default [_allow _side _pre _post _bound-value] false)

(defmethod lenient? :limit-allow-improving
  [_allow side pre post bound-value]
  (and (some? pre)
       (out-of-bounds? side pre bound-value)
       (improving? side pre post)))

;; ---------------------------------------------------------------------------
;; Composition

(defn- violation-message
  [bound-kind agg]
  (str "Limit " (name bound-kind)
       " " (name (aggregate-kind agg))
       "=" (:value (aggregate-fields agg))))

(defn- bound-side-violation
  "Returns a violation message if `agg` applies to `request` and
  the post-value is out-of-bound on `side`, unless leniency
  excuses it."
  [side agg request allow bound-kind]
  (when (aggregate-applies? agg request)
    (let [bound-value (num-value (:value (aggregate-fields agg)))
          post (num-value (:value request))
          pre (some-> (:pre-value request)
                      num-value)]
      (when (and (out-of-bounds? side post bound-value)
                 (not (lenient? allow side pre post bound-value)))
        (violation-message bound-kind agg)))))

(defn- bound-violation
  [bound request allow]
  (let [bound-kind (match/variant (:kind bound))]
    (some (fn [{:keys [side aggregate]}]
            (bound-side-violation side aggregate request allow bound-kind))
          (bound-sides bound))))

(defn- violation
  [limits request]
  (some (fn [{:keys [bound allow] :as limit}]
          (when-let [msg (bound-violation bound request allow)]
            {:limit limit :msg msg}))
        limits))

(defn- enabled-limits
  [policies]
  (->> policies
       (filter domain/live?)
       (mapcat :limits)))

(defn sides-in-force
  [policies kind request]
  (into #{}
        (comp (filter (fn [l] (match/matches? l kind request)))
              (mapcat (fn [{:keys [bound]}] (bound-sides bound)))
              (filter (fn [{:keys [aggregate]}]
                        (aggregate-applies? aggregate request)))
              (map :side))
        (enabled-limits policies)))

(defn check
  [policies kind request]
  (let [matching (filter (fn [l] (match/matches? l kind request))
                         (enabled-limits policies))
        {:keys [limit msg]} (violation matching request)]
    (if msg
      (error/reject :policy/limit-exceeded
                    {:message (or (:reason limit) msg)
                     :kind kind
                     :request request})
      true)))
