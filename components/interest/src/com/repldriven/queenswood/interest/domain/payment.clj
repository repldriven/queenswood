(ns com.repldriven.queenswood.interest.domain.payment
  (:require
    [com.repldriven.queenswood.calc.interface :as calc]))

(def ^:private units
  {:interest-period-unit-day :day
   :interest-period-unit-week :week
   :interest-period-unit-month :month})

(def ^:private conventions
  {:business-day-convention-unadjusted :unadjusted
   :business-day-convention-following :following
   :business-day-convention-modified-following :modified-following
   :business-day-convention-preceding :preceding
   :business-day-convention-modified-preceding :modified-preceding})

(defn- calc-payment
  "A version's payment kind in calc's vocabulary."
  [kind]
  (let [{:keys [account-day calendar-day]} kind]
    (cond account-day
          {:account-day (update account-day :unit units)}

          calendar-day
          {:calendar-day calendar-day}

          :else
          {:closure-day {}})))

(defn due-key
  "What whether `account` is due under its version's `payment` depends on
  besides the run's window: the version, and the day the account came
  onto it where the payment counts from it or that day falls in the
  window, `after` to `through`, or the days a convention reaches before
  it."
  [account payment after through]
  (let [{:keys [product-id version-id version-from-on]} account
        counts-from-account? (get-in payment [:kind :account-day])
        recent? (> version-from-on (- (or after through) 31))]
    [product-id
     version-id
     (when (or counts-from-account? recent?) version-from-on)]))

(defn due?
  "Whether an account that came onto its version on `from-on` is due a
  payment under the version's `payment` on `calendar`, the calc calendar
  its payment names, in the window after `after` through `through`."
  [payment from-on calendar after through]
  (let [{:keys [kind business-day-convention]} payment]
    (calc/due? (calc-payment kind)
               from-on
               calendar
               (conventions business-day-convention)
               after
               through)))
