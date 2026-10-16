(ns com.repldriven.queenswood.cash-account-product-api.components
  (:require
    [com.repldriven.queenswood.cash-account-product-api.coercion :as coercion]
    [com.repldriven.queenswood.cash-account-product-api.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry]]))

(def TemplateId (schema/id-schema "TemplateId" "tpl" examples/TemplateId))

(def BalanceSheetSide
  (coercion/balance-sheet-side-enum-schema {:json-schema/example "liability"}))

(def VersionStatus
  (coercion/version-status-enum-schema {:json-schema/example "draft"}))

(def OpeningReward
  [:map
   {:closed true
    :json-schema/example examples/OpeningReward
    :description
    "The welcome reward paid once to an account opened under the version,
    in minor units of the version's currency. Positive, and fixed once
    the version is published."}
   [:amount [:ref "MinorUnits"]]])

(def InterestBanding
  (coercion/interest-banding-enum-schema {:json-schema/example "marginal"}))

(def InterestPeriodUnit
  (coercion/interest-period-unit-enum-schema {:json-schema/example "month"}))

(def InterestDayCount
  (coercion/interest-day-count-enum-schema {:json-schema/example "actual-365"}))

(def BusinessDayConvention
  (coercion/business-day-convention-enum-schema {:json-schema/example
                                                 "modified-following"}))

(def BusinessDayCalendar
  (coercion/business-day-calendar-enum-schema
   {:json-schema/example "GBLO"
    :description
    "The days a payment schedule counts as business days: `none`, every
    day; `weekends`, every day but Saturday and Sunday; or an FpML
    business centre, `GBLO` for London's England and Wales bank holidays
    and `EUTA` for TARGET's closing days, each also closed on Saturday
    and Sunday."}))

(def InterestRate
  [:int
   {:min 0
    :max 1000000
    :json-schema/format "int32"
    :json-schema/example 500
    :description
    "A rate a year in basis points, 500 for 5.00%. Not negative."}])

(def ^:private band-starts
  [:vector
   {:min 1
    :max 10
    :description
    "Each band's lowest balance in minor units, the first 0 and each above
    the one before."}
   [:ref "MinorUnits"]])

(def ^:private rates
  [:vector
   {:min 1
    :max 10
    :description "One rate a year for each band, in basis points."}
   [:ref "InterestRate"]])

(def RelativeInterestAccrual
  [:map
   {:closed true
    :description
    "A grid of rates whose periods start `after` a number of the
    schedule's `unit` from the day the account came onto the version,
    the first after 0. Adding months keeps the day of the month, or the
    month's last day where it has fewer."}
   [:basis [:enum "relative"]]
   [:banding [:ref "InterestBanding"]]
   [:bands band-starts]
   [:unit [:ref "InterestPeriodUnit"]]
   [:periods
    [:vector {:min 1 :max 12}
     [:map {:closed true}
      [:after [:int {:min 0 :max 36600}]]
      [:rates-bps rates]]]]])

(def FixedInterestAccrual
  [:map
   {:closed true
    :description
    "A grid of rates whose periods start on a date, the first on the
    version's `effective-from`."}
   [:basis [:enum "fixed"]]
   [:banding [:ref "InterestBanding"]]
   [:bands band-starts]
   [:periods
    [:vector {:min 1 :max 12}
     [:map {:closed true}
      [:from-on [:ref "BusinessDay"]]
      [:rates-bps rates]]]]])

(def InterestAccrual
  [:multi
   {:dispatch :basis
    :json-schema/example (:accrual examples/InterestTerms)
    :json-schema/discriminator
    {:propertyName "basis"
     :mapping {"relative" "#/components/schemas/RelativeInterestAccrual"
               "fixed" "#/components/schemas/FixedInterestAccrual"}}
    :description
    "How interest is earned: a rate a year for each period of an
    account's life and each band of its balance, accrued on each day's
    end-of-day balance. `bands` holds each band's lowest balance in
    minor units, from 0 and strictly increasing, the last without a
    top. Each period carries one rate for each band, and the periods
    start strictly one after another. `marginal` banding pays each
    band's rate on the part of the balance within it, and
    `whole-balance` the rate of the band the balance falls in on all of
    it. No rate is negative."}
   ["relative" [:ref "RelativeInterestAccrual"]]
   ["fixed" [:ref "FixedInterestAccrual"]]])

(def AccountDayInterestPayment
  [:map
   {:closed true
    :description
    "Interest paid every `every` of `unit`, counted from the day the
    account came onto the version: every 1 day is daily, and every 3
    months quarterly on the account's day. A day `calendar` does not
    count moves by the currency's business day convention: `none`, every
    day, where it names none, or one of its template's
    `currency-calendars` for the currency."}
   [:kind [:enum "account-day"]]
   [:unit [:ref "InterestPeriodUnit"]]
   [:every [:int {:min 1 :max 366}]]
   [:calendar {:optional true} [:ref "BusinessDayCalendar"]]])

(def CalendarDayInterestPayment
  [:map
   {:closed true
    :description
    "Interest paid on `day-of-month`, or the month's last day where it
    has fewer, in `in-month` and every `every` months from it: 31 is
    the last day of each month. A day `calendar` does not count moves by
    the currency's business day convention: `none`, every day, where it
    names none, or one of its template's `currency-calendars` for the
    currency."}
   [:kind [:enum "calendar-day"]]
   [:every [:enum 1 2 3 4 6 12]]
   [:day-of-month [:int {:min 1 :max 31}]]
   [:in-month [:int {:min 1 :max 12}]]
   [:calendar {:optional true} [:ref "BusinessDayCalendar"]]])

(def ClosureDayInterestPayment
  [:map
   {:closed true :description "Interest paid once, when the account closes."}
   [:kind [:enum "closure-day"]]])

(def InterestPayment
  [:multi
   {:dispatch :kind
    :json-schema/example (:payment examples/InterestTerms)
    :json-schema/discriminator
    {:propertyName "kind"
     :mapping {"account-day" "#/components/schemas/AccountDayInterestPayment"
               "calendar-day" "#/components/schemas/CalendarDayInterestPayment"
               "closure-day" "#/components/schemas/ClosureDayInterestPayment"}}
    :description "When accrued interest is paid into the account."}
   ["account-day" [:ref "AccountDayInterestPayment"]]
   ["calendar-day" [:ref "CalendarDayInterestPayment"]]
   ["closure-day" [:ref "ClosureDayInterestPayment"]]])

(def InterestTerms
  [:map
   {:closed true
    :json-schema/example examples/InterestTerms
    :description
    "The version's interest: how it is earned and when it is paid.
    Fixed once the version is published."}
   [:accrual [:ref "InterestAccrual"]]
   [:payment [:ref "InterestPayment"]]])

(def PayClosureInterest
  [:map
   {:closed true
    :description "Interest accrued and not yet paid is paid at closure."}
   [:kind [:enum "pay"]]])

(def ForfeitClosureInterest
  [:map
   {:closed true
    :description "Interest accrued and not yet paid is forfeited at closure."}
   [:kind [:enum "forfeit"]]])

(def ForfeitDaysClosureInterest
  [:map
   {:closed true
    :description
    "`days` of interest at the closure day's rates are forfeited, never
    more than has accrued and not yet been paid, and the rest is paid."}
   [:kind [:enum "forfeit-days"]]
   [:days [:int {:min 1 :max 366}]]])

(def ClosureInterest
  [:multi
   {:dispatch :kind
    :json-schema/example (:interest examples/ClosureTerms)
    :json-schema/discriminator
    {:propertyName "kind"
     :mapping {"pay" "#/components/schemas/PayClosureInterest"
               "forfeit" "#/components/schemas/ForfeitClosureInterest"
               "forfeit-days"
               "#/components/schemas/ForfeitDaysClosureInterest"}}
    :description
    "What a closure does with interest accrued and not yet paid. Only a
    version that pays interest can forfeit it, and one paid on its
    `closure-day` cannot `forfeit` all of it."}
   ["pay" [:ref "PayClosureInterest"]]
   ["forfeit" [:ref "ForfeitClosureInterest"]]
   ["forfeit-days" [:ref "ForfeitDaysClosureInterest"]]])

(def FlatClosureFee
  [:map
   {:closed true
    :description "A fee of `amount` in minor units of the version's currency."}
   [:kind [:enum "flat"]]
   [:amount [:int {:min 1}]]])

(def BalanceClosureFee
  [:map
   {:closed true
    :description
    "A fee of `rate-bps` basis points of the balance closed, no less than
    `min` and no more than `max`, each in minor units, where given."}
   [:kind [:enum "balance"]]
   [:rate-bps [:int {:min 1 :max 10000 :json-schema/format "int32"}]]
   [:min {:optional true} [:int {:min 1}]]
   [:max {:optional true} [:int {:min 1}]]])

(def ClosureFee
  [:multi
   {:dispatch :kind
    :json-schema/discriminator
    {:propertyName "kind"
     :mapping {"flat" "#/components/schemas/FlatClosureFee"
               "balance" "#/components/schemas/BalanceClosureFee"}}
    :description
    "A fee charged at closure, never more than the balance left once the
    interest is settled."}
   ["flat" [:ref "FlatClosureFee"]]
   ["balance" [:ref "BalanceClosureFee"]]])

(def ^:private window-count
  [:int {:min 1 :max 36600 :description "Units after the version's day."}])

(def RefusedEarlyClosure
  [:map
   {:closed true
    :description
    "No closure before the account has been on the version `count` of
    `unit`."}
   [:kind [:enum "refused"]]
   [:unit [:ref "InterestPeriodUnit"]]
   [:count window-count]])

(def ChargedEarlyClosure
  [:map
   {:closed true
    :description
    "A closure before the account has been on the version `count` of
    `unit` settles its interest by `interest` and is charged `fee`."}
   [:kind [:enum "charged"]]
   [:unit [:ref "InterestPeriodUnit"]]
   [:count window-count]
   [:interest [:ref "ClosureInterest"]]
   [:fee {:optional true} [:ref "ClosureFee"]]])

(def EarlyClosure
  [:multi
   {:dispatch :kind
    :json-schema/discriminator
    {:propertyName "kind"
     :mapping {"refused" "#/components/schemas/RefusedEarlyClosure"
               "charged" "#/components/schemas/ChargedEarlyClosure"}}
    :description
    "A window from the day the account came onto the version, in which a
    closure is refused or charged."}
   ["refused" [:ref "RefusedEarlyClosure"]]
   ["charged" [:ref "ChargedEarlyClosure"]]])

(def ClosureTerms
  [:map
   {:closed true
    :json-schema/example examples/ClosureTerms
    :description
    "What closing an account on the version does. A closure inside the
    first `early` window still open is held to that window, and any
    other to `interest` alone. The windows start in ascending order of
    `count`, all in one `unit`. Where a request states none, a closure
    pays what has accrued and charges nothing. Fixed once the version is
    published."}
   [:interest [:ref "ClosureInterest"]]
   [:early {:optional true} [:vector {:max 12} [:ref "EarlyClosure"]]]])

(def PaymentDirection
  (coercion/payment-direction-enum-schema {:json-schema/example "in"}))

(def PaymentMeasure
  (coercion/payment-measure-enum-schema {:json-schema/example "amount"}))

(def PaymentPeriod
  (coercion/payment-period-enum-schema {:json-schema/example "month"}))

(def PaymentPeriodAnchor
  (coercion/payment-period-anchor-enum-schema {:json-schema/example "account"}))

(def PaymentLimit
  [:map
   {:closed true
    :json-schema/example examples/PaymentLimit
    :description
    "A bound on the payments into or out of an account opened under the
    version: the most that may be paid, as an `amount` in minor units of
    the version's currency or a `count` of payments, over each `period`.
    A period runs from the day the account was opened, or by the
    `calendar`, from the first of January or the `starts-month` and
    `starts-day-of-month` given. A payment `out` counts from when it is
    submitted, and one that fails before it settles gives its allowance
    back. Interest and rewards are not payments. Fixed once the version
    is published."}
   [:direction [:ref "PaymentDirection"]]
   [:measure [:ref "PaymentMeasure"]]
   [:max [:int {:min 0}]]
   [:period [:ref "PaymentPeriod"]]
   [:anchor {:optional true} [:ref "PaymentPeriodAnchor"]]
   [:starts-month {:optional true} [:int {:min 1 :max 12}]]
   [:starts-day-of-month {:optional true} [:int {:min 1 :max 28}]]
   [:reason {:optional true} string?]])

(def CashAccountProductRequest
  [:map {:closed true :json-schema/example examples/CashAccountProductRequest}
   [:name [:ref "Name"]]
   [:template-id [:ref "TemplateId"]]
   [:currency [:ref "Currency"]]
   [:interest-rate-bps {:optional true} [:ref "InterestRate"]]
   [:interest-terms {:optional true} [:ref "InterestTerms"]]
   [:closure-terms {:optional true} [:ref "ClosureTerms"]]
   [:opening-reward {:optional true} [:ref "OpeningReward"]]
   [:payment-limits {:optional true} [:vector [:ref "PaymentLimit"]]]
   [:min-balance {:optional true} [:ref "SignedMinorUnits"]]
   [:max-balance {:optional true} [:ref "SignedMinorUnits"]]
   [:effective-from [:ref "BusinessDay"]]
   [:effective-to {:optional true} [:maybe [:ref "BusinessDay"]]]])

(def CashAccountProductDraftRequest
  [:map {:closed true :json-schema/example examples/CashAccountProductRequest}
   [:name [:ref "Name"]]
   [:template-id {:optional true} [:ref "TemplateId"]]
   [:currency [:ref "Currency"]]
   [:interest-rate-bps {:optional true} [:ref "InterestRate"]]
   [:interest-terms {:optional true} [:ref "InterestTerms"]]
   [:closure-terms {:optional true} [:ref "ClosureTerms"]]
   [:opening-reward {:optional true} [:ref "OpeningReward"]]
   [:payment-limits {:optional true} [:vector [:ref "PaymentLimit"]]]
   [:min-balance {:optional true} [:ref "SignedMinorUnits"]]
   [:max-balance {:optional true} [:ref "SignedMinorUnits"]]
   [:effective-from [:ref "BusinessDay"]]
   [:effective-to {:optional true} [:maybe [:ref "BusinessDay"]]]])

(def CashAccountProductVersion
  [:map {:json-schema/example examples/CashAccountProductVersion}
   [:bank-id [:ref "BankId"]]
   [:product-id [:ref "ProductId"]]
   [:version-id [:ref "VersionId"]]
   [:version-number int?]
   [:status [:ref "VersionStatus"]]
   [:name [:ref "Name"]]
   [:template-id [:ref "TemplateId"]]
   [:product-type [:ref "ProductType"]]
   [:balance-sheet-side [:ref "BalanceSheetSide"]]
   [:currency [:ref "Currency"]]
   [:balance-products [:unique-vector-lax {:min 1} [:ref "BalanceProduct"]]]
   [:allowed-payment-address-schemes
    [:unique-vector-lax {:min 1} [:ref "PaymentAddressScheme"]]]
   [:interest-rate-bps [:ref "InterestRate"]]
   [:interest-terms {:optional true} [:ref "InterestTerms"]]
   [:day-count {:optional true} [:ref "InterestDayCount"]]
   [:business-day-convention {:optional true} [:ref "BusinessDayConvention"]]
   [:closure-terms [:ref "ClosureTerms"]]
   [:opening-reward {:optional true} [:ref "OpeningReward"]]
   [:payment-limits {:optional true} [:vector [:ref "PaymentLimit"]]]
   [:min-balance [:ref "SignedMinorUnits"]]
   [:max-balance {:optional true} [:ref "SignedMinorUnits"]]
   [:effective-from [:ref "BusinessDay"]]
   [:effective-to {:optional true} [:ref "BusinessDay"]]
   [:published-on {:optional true} [:ref "BusinessDay"]]
   [:created-at [:ref "Timestamp"]]
   [:created-by [:ref "Actor"]]
   [:updated-at {:optional true} [:ref "Timestamp"]]
   [:updated-by {:optional true} [:ref "Actor"]]])

(def CashAccountProduct
  [:map {:json-schema/example examples/CashAccountProduct}
   [:product-id [:ref "ProductId"]]
   [:versions [:vector [:ref "CashAccountProductVersion"]]]])

(def ^:private version-keys
  (into [] (comp (filter vector?) (map first)) CashAccountProductVersion))

(defn- ->payment-limit
  "An account-payment limit a version carries, as a product request
  states it, or nil for a limit of another kind."
  [limit]
  (when-some [fields (get-in limit [:kind :account-payment])]
    (let [aggregate (get-in limit [:bound :kind :max :aggregate :kind])
          measure (first (keys aggregate))
          {:keys [value window anchor starts-month starts-day-of-month]}
          (get aggregate measure)]
      (cond-> {:direction (get-in fields [:filters 0 :direction])
               :measure measure
               :max (if (map? value) (:value value) value)
               :period window
               :reason (:reason limit)}
              anchor
              (assoc :anchor anchor)

              starts-month
              (assoc :starts-month starts-month)

              starts-day-of-month
              (assoc :starts-day-of-month starts-day-of-month)))))

(defn- balance-bound
  "The amount a version's balance limit bounds its accounts' available
  balance by on `side`, `:min` or `:max`, or nil where it carries none."
  [version side]
  (some (fn [limit]
          (when (get-in limit [:kind :balance])
            (get-in limit
                    [:bound :kind side :aggregate :kind :amount :value
                     :value])))
        (:limits version)))

(defn- schedule-periods
  "A stored schedule's basis, its unit where it has one, and its
  periods."
  [schedule]
  (let [[basis {:keys [unit periods]}] (first schedule)]
    [basis unit periods]))

(defn- ->interest-terms-body
  "A version's stored interest terms as `InterestTerms` states them."
  [terms]
  (let [{:keys [accrual payment]} terms
        {:keys [banding bands schedule]} accrual
        [basis unit periods] (schedule-periods schedule)
        [kind fields] (first (:kind payment))]
    {:accrual (cond-> {:basis (name basis)
                       :banding banding
                       :bands bands
                       :periods periods}
                      unit
                      (assoc :unit unit))
     :payment (cond-> (assoc fields :kind (name kind))
                      (not= :closure-day kind)
                      (assoc :calendar (:calendar payment)))}))

(defn ->interest-terms
  "The interest terms a request states, as a version stores them: the
  accrual's basis as its schedule's variant, and the payment's kind as
  its own beside the calendar it names."
  [interest-terms]
  (let [{:keys [accrual payment]} interest-terms
        {:keys [basis banding bands unit periods]} accrual]
    {:accrual {:banding banding
               :bands bands
               :schedule (if (= "relative" basis)
                           {:relative {:unit unit :periods periods}}
                           {:fixed {:periods periods}})}
     :payment (cond-> {:kind {(keyword (:kind payment))
                              (dissoc payment :kind :calendar)}}
                      (:calendar payment)
                      (assoc :calendar (:calendar payment)))}))

(defn- ->variant
  "A request's `kind`-discriminated object as a stored oneof."
  [body]
  {:kind {(keyword (:kind body)) (dissoc body :kind)}})

(defn- variant-body
  "A stored oneof as its `kind`-discriminated object."
  [variant]
  (let [[kind fields] (first (:kind variant))]
    (assoc fields :kind (name kind))))

(defn- ->early-closure
  [window]
  (let [{:keys [unit count kind interest fee]} window]
    {:unit unit
     :count count
     :kind (if (= "refused" kind)
             {:refused {}}
             {:charged (cond-> {:interest (->variant interest)}
                               fee
                               (assoc :fee (->variant fee)))})}))

(defn- early-closure-body
  [window]
  (let [{:keys [unit count kind]} window
        [variant {:keys [interest fee]}] (first kind)]
    (cond-> {:unit unit :count count :kind (name variant)}
            interest
            (assoc :interest (variant-body interest))

            fee
            (assoc :fee (variant-body fee)))))

(defn ->closure-terms
  "The closure terms a request states, as a version stores them: each
  `kind` as its oneof's variant."
  [closure-terms]
  (let [{:keys [interest early]} closure-terms]
    (cond-> {:interest (->variant interest)}
            (seq early)
            (assoc :early (mapv ->early-closure early)))))

(defn- ->closure-terms-body
  "A version's stored closure terms as `ClosureTerms` states them."
  [closure-terms]
  (let [{:keys [interest early]} closure-terms]
    (cond-> {:interest (variant-body interest)}
            (seq early)
            (assoc :early (mapv early-closure-body early)))))

(defn ->version-data
  "A product request's body as the domain takes it."
  [body]
  (cond-> body
          (:interest-terms body)
          (update :interest-terms ->interest-terms)

          (:closure-terms body)
          (update :closure-terms ->closure-terms)))

(defn- first-rate
  "The rate of a version's first period and first band, or 0 where it
  pays none."
  [version]
  (let [[_ _ periods] (schedule-periods (get-in version
                                                [:interest-terms :accrual
                                                 :schedule]))]
    (or (first (:rates-bps (first periods))) 0)))

(defn ->version-body
  [version]
  (let [opening (some (fn [{:keys [kind] :as terms}]
                        (when (= :reward-kind-opening kind) terms))
                      (:reward-terms version))
        payment-limits (into [] (keep ->payment-limit) (:limits version))
        max-balance (balance-bound version :max)
        interest-terms (:interest-terms version)]
    (cond-> (assoc (select-keys version version-keys)
                   :interest-rate-bps
                   (first-rate version)
                   :min-balance
                   (or (balance-bound version :min) 0))
            (:closure-terms version)
            (assoc :closure-terms
                   (->closure-terms-body (:closure-terms version)))

            interest-terms
            (assoc :interest-terms
                   (->interest-terms-body interest-terms)
                   :day-count
                   (get-in interest-terms [:accrual :day-count])
                   :business-day-convention
                   (get-in interest-terms [:payment :business-day-convention]))

            opening
            (assoc :opening-reward {:amount (:amount opening)})

            (seq payment-limits)
            (assoc :payment-limits payment-limits)

            max-balance
            (assoc :max-balance max-balance))))

(defn ->template-body
  [template]
  (let [{:keys [currencies]} template]
    (-> template
        (dissoc :currencies)
        (assoc :allowed-currencies (mapv :currency currencies)
               :currency-calendars (mapv (fn [{:keys [currency calendars]}]
                                           {:currency currency
                                            :calendars
                                            (into
                                             [:business-day-calendar-none]
                                             calendars)})
                                         currencies)))))

(defn ->product-body
  [product]
  {:product-id (:product-id product)
   :versions (mapv ->version-body (:versions product))})

(def CashAccountProductList
  (schema/list-schema "CashAccountProduct" examples/CashAccountProductList))

(def CashAccountProductTemplate
  [:map {:json-schema/example examples/CashAccountProductTemplate}
   [:template-id [:ref "TemplateId"]]
   [:name {:optional true} [:ref "Name"]]
   [:product-type [:ref "ProductType"]]
   [:balance-sheet-side [:ref "BalanceSheetSide"]]
   [:allowed-currencies [:unique-vector-lax {:min 1} [:ref "Currency"]]]
   [:currency-calendars
    [:vector
     {:description
      "Each allowed currency's calendars, the ones a version in it may
      count its payment days on, `none` first."}
     [:map
      [:currency [:ref "Currency"]]
      [:calendars [:vector [:ref "BusinessDayCalendar"]]]]]]
   [:balance-products [:unique-vector-lax {:min 1} [:ref "BalanceProduct"]]]
   [:allowed-payment-address-schemes
    [:vector [:ref "PaymentAddressScheme"]]]])

(def CashAccountProductTemplateList
  [:map {:json-schema/example examples/CashAccountProductTemplateList}
   [:items [:vector [:ref "CashAccountProductTemplate"]]]])

(def registry
  (components-registry
   [#'TemplateId #'BalanceSheetSide #'VersionStatus #'OpeningReward
    #'InterestRate #'InterestBanding #'InterestPeriodUnit #'InterestDayCount
    #'BusinessDayConvention #'BusinessDayCalendar #'RelativeInterestAccrual
    #'FixedInterestAccrual #'InterestAccrual #'AccountDayInterestPayment
    #'CalendarDayInterestPayment #'ClosureDayInterestPayment #'InterestPayment
    #'InterestTerms #'PayClosureInterest #'ForfeitClosureInterest
    #'ForfeitDaysClosureInterest #'ClosureInterest #'FlatClosureFee
    #'BalanceClosureFee #'ClosureFee #'RefusedEarlyClosure #'ChargedEarlyClosure
    #'EarlyClosure #'ClosureTerms #'PaymentDirection #'PaymentMeasure
    #'PaymentPeriod #'PaymentPeriodAnchor #'PaymentLimit
    #'CashAccountProductRequest #'CashAccountProductDraftRequest
    #'CashAccountProductVersion #'CashAccountProduct #'CashAccountProductList
    #'CashAccountProductTemplate #'CashAccountProductTemplateList]))
