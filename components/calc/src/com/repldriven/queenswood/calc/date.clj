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
