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
   [:interest-rate-bps {:optional true} [:ref "SignedBasisPoints"]]
   [:opening-reward {:optional true} [:ref "OpeningReward"]]
   [:payment-limits {:optional true} [:vector [:ref "PaymentLimit"]]]
   [:effective-from [:ref "BusinessDay"]]
   [:effective-to {:optional true} [:maybe [:ref "BusinessDay"]]]])

(def CashAccountProductDraftRequest
  [:map {:closed true :json-schema/example examples/CashAccountProductRequest}
   [:name [:ref "Name"]]
   [:template-id {:optional true} [:ref "TemplateId"]]
   [:currency [:ref "Currency"]]
   [:interest-rate-bps {:optional true} [:ref "SignedBasisPoints"]]
   [:opening-reward {:optional true} [:ref "OpeningReward"]]
   [:payment-limits {:optional true} [:vector [:ref "PaymentLimit"]]]
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
   [:interest-rate-bps [:ref "SignedBasisPoints"]]
   [:opening-reward {:optional true} [:ref "OpeningReward"]]
   [:payment-limits {:optional true} [:vector [:ref "PaymentLimit"]]]
   [:effective-from [:ref "BusinessDay"]]
   [:effective-to {:optional true} [:ref "BusinessDay"]]
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

(defn ->version-body
  [version]
  (let [opening (some (fn [{:keys [kind] :as terms}]
                        (when (= :reward-kind-opening kind) terms))
                      (:reward-terms version))
        payment-limits (into [] (keep ->payment-limit) (:limits version))]
    (cond-> (assoc (select-keys version version-keys)
                   :interest-rate-bps
                   (or (get-in version
                               [:interest-terms :steps 0 :bands 0 :rate-bps])
                       0))
            opening
            (assoc :opening-reward {:amount (:amount opening)})

            (seq payment-limits)
            (assoc :payment-limits payment-limits))))

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
   [:balance-products [:unique-vector-lax {:min 1} [:ref "BalanceProduct"]]]
   [:allowed-payment-address-schemes
    [:vector [:ref "PaymentAddressScheme"]]]])

(def CashAccountProductTemplateList
  [:map {:json-schema/example examples/CashAccountProductTemplateList}
   [:items [:vector [:ref "CashAccountProductTemplate"]]]])

(def registry
  (components-registry
   [#'TemplateId #'BalanceSheetSide #'VersionStatus #'OpeningReward
    #'PaymentDirection #'PaymentMeasure #'PaymentPeriod #'PaymentPeriodAnchor
    #'PaymentLimit #'CashAccountProductRequest #'CashAccountProductDraftRequest
    #'CashAccountProductVersion #'CashAccountProduct #'CashAccountProductList
    #'CashAccountProductTemplate #'CashAccountProductTemplateList]))
