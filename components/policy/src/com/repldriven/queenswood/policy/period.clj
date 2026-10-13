(ns com.repldriven.queenswood.policy.period
  (:import
    (java.time LocalDate)
    (java.time.temporal ChronoUnit)))

(def ^:private day-ms (* 24 60 60 1000))

(def ^:private months
  "How many months a window's period spans, for the windows counted in
  months."
  {:time-window-monthly 1 :time-window-quarterly 3 :time-window-annually 12})

(defn- calendar-anchor
  "A date the calendar's periods are counted from: the first of January,
  or the month and day the period names, in a year that began on a
  Monday, so its weeks start on one."
  [{:keys [starts-month starts-day-of-month]}]
  (LocalDate/of 2018
                (int (or starts-month 1))
                (int (or starts-day-of-month 1))))

(defn- anchor-date
  [period opened-on]
  (if (and opened-on (not= :window-anchor-calendar (:anchor period)))
    (LocalDate/ofEpochDay opened-on)
    (calendar-anchor period)))

(defn- months-start
  "The start of the `step`-month period from `anchor` that `date` falls
  in, stepping back where `date` is before `anchor`."
  [^LocalDate anchor ^LocalDate date step]
  (let [elapsed (.between ChronoUnit/MONTHS anchor date)
        steps (Math/floorDiv (long elapsed) (long step))
        start (.plusMonths anchor (* step steps))]
    (if (.isAfter start date) (.plusMonths anchor (* step (dec steps))) start)))

(defn start
  [period day opened-on]
  (let [{:keys [window rolling-window-ms]} period
        anchor (anchor-date period opened-on)
        date (LocalDate/ofEpochDay day)]
    (cond
     (contains? months window)
     (.toEpochDay (months-start anchor date (months window)))

     (= :time-window-weekly window)
     (- day (Math/floorMod (long (- day (.toEpochDay anchor))) 7))

     (= :time-window-rolling window)
     (- day (quot (or rolling-window-ms 0) day-ms))

     :else
     day)))
