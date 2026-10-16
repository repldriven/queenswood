(ns com.repldriven.queenswood.interest.calendars
  (:require
    [com.repldriven.queenswood.calc.interface :as calc]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]

    [clojure.java.io :as io]
    [clojure.string :as str]))

(def ^:private gblo-resource "calendars/gblo.json")

(defn- iso->epoch-day
  [iso-date]
  (let [[year month day] (map parse-long (str/split iso-date #"-"))]
    (calc/epoch-day year month day)))

(def ^:private gblo-holidays
  "GBLO's `:holidays`, the epoch days of England and Wales's bank
  holidays as the resource lists them, or the anomaly reading it failed
  with."
  (delay (let-nom> [text (error/try-nom :interest/calendars
                                        "Failed to read GBLO's holidays"
                                        (slurp (io/resource gblo-resource)))
                    data (json/read-str text :key-fn keyword)]
           {:holidays (into (sorted-set)
                            (map (comp iso->epoch-day :date))
                            (:holidays data))})))

(defn calendars
  "Each calendar a payment may name, as calc's calendars, for a run on
  `business-day`, or an anomaly where GBLO's holidays cannot be read.
  TARGET's closing days are those of the day's year and the years either
  side; GBLO's are the resource's, and a day past its last listed year
  is warned of, since it counts that year's days by weekends alone."
  [business-day]
  (let-nom> [{gblo :holidays} @gblo-holidays]
    (let [year (calc/year-of business-day)]
      (when (and (seq gblo) (> year (calc/year-of (last gblo))))
        (log/warnf "GBLO's holidays end in %s, before the run's day %s"
                   (calc/year-of (last gblo))
                   business-day))
      {:business-day-calendar-none {}
       :business-day-calendar-weekends calc/weekends
       :business-day-calendar-gblo (assoc calc/weekends :holidays gblo)
       :business-day-calendar-euta (calc/target [(dec year) year
                                                 (inc year)])})))
