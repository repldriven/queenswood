(ns com.repldriven.queenswood.calc.interest
  (:require
    [com.repldriven.queenswood.calc.date :as date]))

(def ^:private micro-per-minor 1000000)

(def ^:private micro-per-bps (quot micro-per-minor 10000))

(defn year-basis
  [day-count epoch-day]
  (case day-count
    :actual-365 365
    :actual-360 360
    :actual-actual (date/days-in-year epoch-day)
    nil))

(defn- period-start
  [schedule from-on period]
  (let [{:keys [relative]} schedule]
    (if relative
      (date/plus-units from-on (:unit relative) (:after period))
      (:from-on period))))

(defn period-in-force
  [schedule from-on epoch-day]
  (let [{:keys [relative fixed]} schedule]
    (reduce (fn [in-force period]
              (let [start (period-start schedule from-on period)]
                (if (and start (<= start epoch-day)) period in-force)))
            nil
            (:periods (or relative fixed)))))

(defn band-portions
  [banding bands principal]
  (case banding
    :marginal (mapv
               (fn [lower upper]
                 (max 0 (- (if upper (min principal upper) principal) lower)))
               bands
               (concat (rest bands) [nil]))
    :whole-balance (let [band (last (keep-indexed (fn [i lower]
                                                    (when (<= lower principal)
                                                      i))
                                                  bands))]
                     (mapv (fn [i] (if (= band i) principal 0))
                           (range (count bands))))
    nil))

(defn annual-micro
  [portions rates-bps]
  (* micro-per-bps (reduce + 0 (map * portions rates-bps))))

(defn day-share
  [annual-micro carry basis]
  (let [daily (quot (+ annual-micro (* carry basis)) basis)]
    {:amount (quot daily micro-per-minor)
     :carry (rem daily micro-per-minor)}))

(defn day-rates
  [accrual from-on epoch-day]
  (let [{:keys [banding day-count bands schedule]} accrual
        period (period-in-force schedule from-on epoch-day)
        basis (year-basis day-count epoch-day)]
    (when (and period basis)
      {:banding banding
       :bands bands
       :rates-bps (:rates-bps period)
       :basis basis})))

(defn accrue
  [day-rates principal carry]
  (let [{:keys [banding bands rates-bps basis]} day-rates
        portions (band-portions banding bands principal)]
    (when portions
      (let [annual (annual-micro portions rates-bps)]
        (when-not (zero? annual)
          (day-share annual carry basis))))))
