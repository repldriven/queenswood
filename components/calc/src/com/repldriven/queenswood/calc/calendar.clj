(ns com.repldriven.queenswood.calc.calendar
  (:require
    [com.repldriven.queenswood.calc.date :as date]))

(def ^:private saturday-and-sunday #{6 7})

(def ^:private longest-run
  "The most days in a row a calendar may close before `adjust` gives up."
  31)

(def weekends {:weekends saturday-and-sunday :holidays #{}})

(defn target-closing-days
  [year]
  (let [easter (date/easter-sunday year)]
    #{(date/epoch-day year 1 1) (- easter 2) (inc easter)
      (date/epoch-day year 5 1) (date/epoch-day year 12 25)
      (date/epoch-day year 12 26)}))

(defn target
  [years]
  {:weekends saturday-and-sunday
   :holidays (into #{} (mapcat target-closing-days) years)})

(defn business-day?
  [calendar epoch-day]
  (let [{:keys [weekends holidays]} calendar]
    (not (or (contains? weekends (date/day-of-week epoch-day))
             (contains? holidays epoch-day)))))

(defn- roll
  [calendar epoch-day step]
  (->> (iterate (fn [day] (+ day step)) epoch-day)
       (take (inc longest-run))
       (filter (fn [day] (business-day? calendar day)))
       first))

(defn- modified
  [calendar epoch-day step]
  (let [rolled (roll calendar epoch-day step)]
    (if (and rolled (= (date/month-of rolled) (date/month-of epoch-day)))
      rolled
      (roll calendar epoch-day (- step)))))

(defn adjust
  [convention calendar epoch-day]
  (case convention
    :unadjusted epoch-day
    :following (roll calendar epoch-day 1)
    :preceding (roll calendar epoch-day -1)
    :modified-following (modified calendar epoch-day 1)
    :modified-preceding (modified calendar epoch-day -1)
    nil))
