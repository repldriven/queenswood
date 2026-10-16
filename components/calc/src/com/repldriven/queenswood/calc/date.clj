(ns com.repldriven.queenswood.calc.date
  (:import
    (java.time LocalDate)))

(defn days-in-year
  [epoch-day]
  (.lengthOfYear (LocalDate/ofEpochDay epoch-day)))

(defn plus-units
  [epoch-day unit n]
  (let [date (LocalDate/ofEpochDay epoch-day)
        n (long n)]
    (some-> (case unit
              :day (.plusDays date n)
              :week (.plusWeeks date n)
              :month (.plusMonths date n)
              nil)
            .toEpochDay)))

(defn day-of-week
  [epoch-day]
  (.getValue (.getDayOfWeek (LocalDate/ofEpochDay epoch-day))))

(defn month-of
  [epoch-day]
  (.getMonthValue (LocalDate/ofEpochDay epoch-day)))

(defn epoch-day
  [year month day]
  (.toEpochDay (LocalDate/of (int year) (int month) (int day))))

(defn easter-sunday
  [year]
  (let [a (mod year 19)
        b (quot year 100)
        c (mod year 100)
        d (quot b 4)
        e (mod b 4)
        f (quot (+ b 8) 25)
        g (quot (+ (- b f) 1) 3)
        h (mod (+ (* 19 a) (- b d g) 15) 30)
        i (quot c 4)
        k (mod c 4)
        l (mod (- (+ 32 (* 2 e) (* 2 i)) h k) 7)
        m (quot (+ a (* 11 h) (* 22 l)) 451)
        month (quot (+ h l (* -7 m) 114) 31)
        day (inc (mod (+ h l (* -7 m) 114) 31))]
    (epoch-day year month day)))
