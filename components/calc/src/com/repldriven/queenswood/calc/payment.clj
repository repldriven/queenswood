(ns com.repldriven.queenswood.calc.payment
  (:require
    [com.repldriven.queenswood.calc.calendar :as calendar]
    [com.repldriven.queenswood.calc.date :as date]))

(def ^:private adjust-reach
  "The most days a convention moves a payment day, so a scheduled day
  this far outside a window may still land in it."
  31)

(defn- daily?
  "Whether an account-day payment is paid every day, the account's first
  among them."
  [{:keys [unit every]}]
  (and (= :day unit) (= 1 every)))

(defn- account-days
  [{:keys [unit every] :as account-day} from-on lo hi]
  (let [step (case unit
               :day every
               :week (* 7 every)
               nil)]
    (if step
      (let [k (max (if (daily? account-day) 0 1)
                   (long (Math/ceil (/ (- lo from-on) (double step)))))]
        (take-while (fn [day] (<= day hi))
                    (iterate (fn [day] (+ day step)) (+ from-on (* k step)))))
      (let [months-before (quot (- lo from-on) 31)
            k (max 1 (quot months-before every))]
        (->> (iterate inc k)
             (map (fn [n] (date/plus-units from-on :month (* n every))))
             (drop-while (fn [day] (< day lo)))
             (take-while (fn [day] (<= day hi))))))))

(defn- calendar-days
  [{:keys [every day-of-month in-month]} lo hi]
  (let [month-index (fn [day]
                      (+ (* 12 (date/year-of day)) (dec (date/month-of day))))]
    (for [i (range (month-index lo) (inc (month-index hi)))
          :let [year (quot i 12)
                month (inc (rem i 12))]
          :when (zero? (mod (- month in-month) every))
          :let [first-of (date/epoch-day year month 1)
                last-day (date/days-in-month first-of)
                day (+ first-of (dec (min day-of-month last-day)))]
          :when (<= lo day hi)]
      day)))

(defn scheduled-days
  [payment from-on lo hi]
  (let [{:keys [account-day calendar-day]} payment
        first-day (when from-on
                    (if (and account-day (daily? account-day))
                      from-on
                      (inc from-on)))
        lo (if first-day (max lo first-day) lo)]
    (cond (and account-day from-on)
          (account-days account-day from-on lo hi)

          calendar-day
          (calendar-days calendar-day lo hi)

          :else
          [])))

(defn due?
  [payment from-on calendar convention after through]
  (let [after (or after (dec through))]
    (boolean (some (fn [day]
                     (when-some [moved (calendar/adjust convention
                                                        calendar
                                                        day)]
                       (< after moved (inc through))))
                   (scheduled-days payment
                                   from-on
                                   (- (inc after) adjust-reach)
                                   (+ through adjust-reach))))))
