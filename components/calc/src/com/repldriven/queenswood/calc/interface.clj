(ns com.repldriven.queenswood.calc.interface
  "Pure calculations on dates and money, in integers: date arithmetic on
  epoch days, and interest built up from it — a day count's year, the
  period of a rate schedule in force, a balance split across bands, and
  a day's accrual in millionths of a minor unit with the remainder
  carried to the next day. Nothing here reads or writes state or names a
  record: each function takes every value it needs, in this component's
  own vocabulary of plain keywords."
  (:require
    [com.repldriven.queenswood.calc.date :as date]
    [com.repldriven.queenswood.calc.interest :as interest]))

;; ---------------------------------------------------------------------------
;; Dates

(defn days-in-year
  "The number of days, 365 or 366, in the calendar year holding
  `epoch-day`."
  [epoch-day]
  (date/days-in-year epoch-day))

(defn plus-units
  "The epoch day `n` units after `epoch-day`, where `unit` is `:day`,
  `:week` or `:month`. A month lands on the same day of the month, or on
  the month's last day where it has fewer. Nil for any other unit."
  [epoch-day unit n]
  (date/plus-units epoch-day unit n))

;; ---------------------------------------------------------------------------
;; Interest

(defn year-basis
  "The days a year's rate is divided by on `epoch-day` under
  `day-count`: 365 for `:actual-365`, 360 for `:actual-360`, and the
  days in that day's year for `:actual-actual`. Nil for any other day
  count."
  [day-count epoch-day]
  (interest/year-basis day-count epoch-day))

(defn period-in-force
  "The period of `schedule` whose rates hold on `epoch-day`: the latest
  to have started by then, or nil where none has. A schedule is
  `{:relative {:unit u :periods [{:after n :rates-bps [...]} ...]}}`,
  each period starting `n` units of `u` after `from-on`, or
  `{:fixed {:periods [{:from-on epoch-day :rates-bps [...]} ...]}}`."
  [schedule from-on epoch-day]
  (interest/period-in-force schedule from-on epoch-day))

(defn band-portions
  "`principal` split across `bands`, each band's lowest balance in
  ascending order from zero, as one portion per band. `:marginal` puts
  in each band the part of the principal between its lowest balance and
  the next band's; `:whole-balance` puts the whole principal in the
  highest band it reaches. A principal at or below zero has no part in
  any band. Nil for any other banding."
  [banding bands principal]
  (interest/band-portions banding bands principal))

(defn annual-micro
  "A year's interest, in millionths of a minor unit, on `portions` at
  `rates-bps`, one rate in basis points for each portion."
  [portions rates-bps]
  (interest/annual-micro portions rates-bps))

(defn day-share
  "One day's share of `annual-micro` over a year of `basis` days,
  opening with `carry`, the millionths of a minor unit an earlier day
  left: `{:amount whole-minor-units :carry millionths-left}`. The carry
  is a day's residue whatever the year's length, so it passes from a
  365-day year to a 366-day one exactly."
  [annual-micro carry basis]
  (interest/day-share annual-micro carry basis))

(defn day-rates
  "What a day's accrual under `accrual`, `{:banding :day-count :bands
  :schedule}`, takes from the version and the day alone: `{:banding
  :bands :rates-bps :basis}`, the rates of the period in force on
  `epoch-day`, counted from `from-on` where the schedule is relative,
  and the year's days under its day count. Every account on the version
  that came onto it on `from-on` accrues against the same day rates, so
  a caller works them out once for each. Nil where no period is in
  force or the day count is one it does not know."
  [accrual from-on epoch-day]
  (interest/day-rates accrual from-on epoch-day))

(defn accrue
  "A day's interest on `principal` at `day-rates`, opening with `carry`:
  `{:amount whole-minor-units :carry millionths-left}`, or nil where
  nothing accrues: a principal in no band, rates of zero, or a banding
  it does not know."
  [day-rates principal carry]
  (interest/accrue day-rates principal carry))
