(ns com.repldriven.queenswood.test-model.interest
  (:require
    [com.repldriven.queenswood.test-model.policies :as policies]
    [com.repldriven.queenswood.test-model.state :as state]

    [clojure.test.check.generators :as gen])
  (:import
    (java.time LocalDate)
    (java.time.temporal ChronoUnit)))

(def ^:private micro-scale 1000000)

(def ^:private day-counts
  "Each currency's day count, as its template entry gives it."
  {"GBP" :actual-365 "EUR" :actual-360 "USD" :actual-360})

(defn- year-days
  [day-count day]
  (case day-count
    :actual-365 365
    :actual-360 360
    :actual-actual (.lengthOfYear (LocalDate/ofEpochDay day))))

(defn- period-start
  [schedule from-on period]
  (if-let [{:keys [unit]} (:relative schedule)]
    (when from-on
      (let [date (LocalDate/ofEpochDay from-on)
            after (long (:after period))]
        (.toEpochDay (case unit
                       :day (.plusDays date after)
                       :week (.plusDays date (* 7 after))
                       :month (.plusMonths date after)))))
    (:from-on period)))

(defn- rates-on
  "The rates of the latest period of `schedule` to have started by `day`."
  [schedule from-on day]
  (->> (:periods (or (:relative schedule) (:fixed schedule)))
       (filter (fn [period]
                 (let [start (period-start schedule from-on period)]
                   (and start (<= start day)))))
       last
       :rates-bps))

(defn- portions
  "`principal` across `bands`: under `:marginal` the part between each
  band's lowest balance and the next's, under `:whole-balance` all of it
  in the band it falls in."
  [banding bands principal]
  (let [uppers (concat (rest bands) [Long/MAX_VALUE])]
    (map (fn [lower upper]
           (if (= :whole-balance banding)
             (if (and (<= lower principal) (< principal upper)) principal 0)
             (max 0 (- (min principal upper) lower))))
         bands
         uppers)))

(defn daily-interest
  "A day's interest on `account` under `terms` on `day`: whole units and
  the carry left, or nil where nothing accrues."
  [{:keys [available credit-carry version-from-on] :or {credit-carry 0}}
   currency
   terms
   day]
  (let [{:keys [banding bands schedule]} terms
        rates (rates-on schedule version-from-on day)
        annual (*
                (quot micro-scale 10000)
                (reduce + 0 (map * (portions banding bands available) rates)))]
    (when (and rates (not (zero? annual)))
      (let [days (year-days (day-counts currency) day)
            daily-micro (quot (+ annual (* credit-carry days)) days)]
        {:whole-units (quot daily-micro micro-scale)
         :carry (rem daily-micro micro-scale)}))))

(def ^:private conventions
  "Each currency's business day convention, as its template entry gives
  it."
  {"GBP" :modified-following
   "EUR" :modified-following
   "USD" :modified-following})

(def ^:private daily {:account-day {:unit :day :every 1}})

(defn- local-date [day] (LocalDate/ofEpochDay day))

(defn- open?
  "Whether `calendar`, `:none` or `:weekends`, counts `day`."
  [calendar day]
  (not (and (= :weekends calendar)
            (>= (.getValue (.getDayOfWeek (local-date day))) 6))))

(defn- next-open
  [calendar day step]
  (first (filter (fn [d] (open? calendar d))
                 (iterate (fn [d] (+ d step)) day))))

(defn- moved
  "`day` moved to one `calendar` counts by `convention`."
  [convention calendar day]
  (let [same-month? (fn [d]
                      (= (.getMonthValue (local-date d))
                         (.getMonthValue (local-date day))))]
    (case convention
      :unadjusted day
      :following (next-open calendar day 1)
      :preceding (next-open calendar day -1)
      :modified-following (let [d (next-open calendar day 1)]
                            (if (same-month? d) d (next-open calendar day -1)))
      :modified-preceding
      (let [d (next-open calendar day -1)]
        (if (same-month? d) d (next-open calendar day 1))))))

(defn- scheduled?
  "Whether `payment` schedules `day` for an account that came onto its
  version on `from-on`: never before it, nor on it unless paid daily."
  [payment from-on day]
  (let [{:keys [account-day calendar-day]} payment]
    (and from-on
         (if (= {:unit :day :every 1} account-day)
           (>= day from-on)
           (> day from-on))
         (cond
          account-day
          (let [{:keys [unit every]} account-day]
            (case unit
              :day (zero? (mod (- day from-on) every))
              :week (zero? (mod (- day from-on) (* 7 every)))
              :month
              (let [start (local-date from-on)
                    whole (.between ChronoUnit/MONTHS start (local-date day))]
                (some (fn [n]
                        (and (pos? n)
                             (zero? (mod n every))
                             (= day (.toEpochDay (.plusMonths start n)))))
                      [whole (inc whole)]))))

          calendar-day
          (let [{:keys [every day-of-month in-month]} calendar-day
                date (local-date day)]
            (and (zero? (mod (- (.getMonthValue date) in-month) every))
                 (= (.getDayOfMonth date)
                    (min day-of-month (.lengthOfMonth date)))))

          :else
          false))))

(defn- due?
  "Whether an account on `version` is due a payment after `after`
  through `through`, its version's terms paying daily on every day
  where they say nothing."
  [account version after through]
  (let [{:keys [payment calendar] :or {payment daily calendar :none}}
        (:terms version)
        convention (conventions (:currency version))
        after (or after (dec through))]
    (boolean
     (some (fn [day]
             (and (scheduled? payment (:version-from-on account) day)
                  (< after (moved convention calendar day) (inc through))))
           (range (- after 30) (+ through 32))))))

(defn- account-version
  [state account]
  (some (fn [v] (when (= (:version account) (:number v)) v))
        (get-in state [:products (:product account) :versions])))

(defn- customer-account?
  [state acct]
  (let [account (get-in state [:accounts acct])
        product (:product account)
        ptype (get-in state [:products product :product-type])]
    (and ptype
         (not (#{:settlement :internal} ptype))
         (= :open (:status account)))))

(defn- accrue-account
  [day state customer-acct]
  (let [account (get-in state [:accounts customer-acct])
        version (account-version state account)
        result (when-let [terms (:terms version)]
                 (daily-interest account (:currency version) terms day))]
    (cond
     (nil? result)
     state

     (zero? (:whole-units result))
     (assoc-in state [:accounts customer-acct :credit-carry] (:carry result))

     :else
     (let [{:keys [whole-units carry]} result]
       (-> state
           (update-in [:accounts customer-acct :interest-accrued]
                      (fnil + 0)
                      whole-units)
           (assoc-in [:accounts customer-acct :credit-carry] carry)
           (update-in [:accounts customer-acct :transaction-legs]
                      (fnil inc 0)))))))

(defn- accrue-org
  [state bank-id day]
  (let [org (get-in state [:banks bank-id])
        custs (filter (fn [a] (customer-account? state a))
                      (:accounts org))]
    (reduce (partial accrue-account day) state custs)))

(defn- run-once
  [state bank-id date action f]
  (let [runs (get-in state [:banks bank-id :interest-runs action date] 0)]
    (if (and (policies/permitted? state bank-id :interest action)
             (policies/within-count? state
                                     bank-id
                                     :interest
                                     action
                                     :time-window-daily
                                     runs))
      (-> (f state bank-id date)
          (assoc-in [:banks bank-id :interest-runs action date] (inc runs)))
      state)))

(def accrue-interest
  {:run? (fn [state] (seq (state/known-banks state)))
   :args (fn [state]
           (gen/tuple (gen/elements (state/known-banks state))
                      (gen/return (state/next-interest-date state))))
   :next-state (fn [state {[bank-id date] :args}]
                 (-> state
                     (run-once bank-id date :interest-action-accrue accrue-org)
                     (update :next-interest-date inc)))
   :valid? (fn [state {[bank-id] :args}] (contains? (:banks state) bank-id))})

(defn- capitalize-account
  [after day state customer-acct]
  (let [account (get-in state [:accounts customer-acct])
        accrued (:interest-accrued account 0)
        version (account-version state account)]
    (if (or (zero? accrued)
            (not (:terms version))
            (not (due? account version after day)))
      state
      (-> state
          (update-in [:accounts customer-acct :available] + accrued)
          (assoc-in [:accounts customer-acct :interest-accrued] 0)
          (update-in [:accounts customer-acct :transaction-legs]
                     (fnil + 0)
                     2)))))

(defn- last-run-before
  "The latest day before `day` the bank ran capitalisation, or nil."
  [state bank-id day]
  (->> (keys (get-in state
                     [:banks bank-id :interest-runs
                      :interest-action-capitalize]))
       (filter (fn [d] (< d day)))
       (reduce (fn [latest d] (if (or (nil? latest) (> d latest)) d latest))
               nil)))

(defn- capitalize-org
  [state bank-id day]
  (let [org (get-in state [:banks bank-id])
        custs (filter (fn [a] (customer-account? state a))
                      (:accounts org))]
    (reduce (partial capitalize-account (last-run-before state bank-id day) day)
            state
            custs)))

(def capitalize-interest
  {:run? (fn [state] (seq (state/known-banks state)))
   :args (fn [state]
           (gen/tuple (gen/elements (state/known-banks state))
                      (gen/return (state/next-interest-date state))))
   :next-state
   (fn [state {[bank-id date] :args}]
     (-> state
         (run-once bank-id date :interest-action-capitalize capitalize-org)
         (update :next-interest-date inc)))
   :valid? (fn [state {[bank-id] :args}] (contains? (:banks state) bank-id))})
