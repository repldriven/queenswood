(ns com.repldriven.queenswood.cash-account-product.domain
  (:require
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]))

(defn- draft?
  [version]
  (= :version-status-draft (:status version)))

(defn- ensure-draft
  [version]
  (when-not (draft? version)
    (let [{:keys [bank-id product-id version-id status]} version]
      (error/reject :cash-account-product/version-immutable
                    {:message "Version is not a draft and cannot be modified"
                     :bank-id bank-id
                     :product-id product-id
                     :version-id version-id
                     :status status}))))

;; ---------------------------------------------------------------------------
;; Instrument fields — snapshot the resolved template into the version

(defn- template-currency
  "The entry of `template`'s currencies for `currency`, or nil."
  [template currency]
  (some (fn [entry] (when (= currency (:currency entry)) entry))
        (:currencies template)))

(defn- ensure-currency-allowed
  [template currency]
  (when-not (template-currency template currency)
    (error/reject :cash-account-product/currency-not-allowed
                  {:message "Currency not allowed for this template"
                   :currency currency
                   :allowed-currencies (mapv :currency
                                             (:currencies template))})))

(defn- ensure-template-matches
  [template data]
  (let [requested (:template-id data)]
    (when (and requested (not= requested (:template-id template)))
      (error/reject :cash-account-product/template-mismatch
                    {:message "Template does not match the product's template"
                     :template-id (:template-id template)
                     :requested-template-id requested}))))

;; ---------------------------------------------------------------------------
;; Payment limits
;;
;; payment-limits, when present, is a vector of {:direction :measure :max
;; :period}, each bounding the amount or the count of the payments into
;; or out of an account over a period. A version keeps each as a policy
;; limit of the account-payment kind, so the policy engine checks it.

(def ^:private period-names
  {:time-window-instant "payment"
   :time-window-daily "day"
   :time-window-weekly "week"
   :time-window-monthly "month"
   :time-window-quarterly "quarter"
   :time-window-annually "year"})

(defn- invalid-limit
  [message payment-limit]
  (error/reject :cash-account-product/invalid-limit
                {:message message :payment-limit payment-limit}))

(defn- ensure-payment-limit
  [payment-limit]
  (let [{:keys [measure max period anchor starts-month starts-day-of-month]}
        payment-limit]
    (cond
     (neg? max)
     (invalid-limit "A payment limit's maximum cannot be negative"
                    payment-limit)

     (and (= :count measure) (= :time-window-instant period))
     (invalid-limit "A count of payments needs a period longer than a payment"
                    payment-limit)

     (and (or starts-month starts-day-of-month)
          (not= :window-anchor-calendar anchor))
     (invalid-limit "Only a calendar period names the day it starts on"
                    payment-limit)

     :else
     nil)))

(defn- ensure-payment-limits
  [payment-limits]
  (let [invalid (some ensure-payment-limit payment-limits)
        bounded (map (fn [payment-limit] (dissoc payment-limit :max :reason))
                     payment-limits)]
    (cond
     invalid
     invalid

     (and (seq bounded) (not (apply distinct? bounded)))
     (error/reject :cash-account-product/invalid-limit
                   {:message "Two payment limits bound the same thing"})

     :else
     nil)))

(defn- limit-reason
  [currency payment-limit]
  (let [{:keys [direction measure max period]} payment-limit
        out? (= :payment-direction-out direction)]
    (str "The product allows at most "
         (if (= :count measure)
           (str max
                (if (= 1 max) " payment " " payments ")
                (if out? "out" "in"))
           (str max " " currency " minor units paid " (if out? "out" "in")))
         " per "
         (period-names period))))

(defn- payment-limit->limit
  [currency payment-limit]
  (let [{:keys [direction measure max period anchor starts-month
                starts-day-of-month reason]}
        payment-limit
        value (if (= :amount measure) {:value max :currency currency} max)]
    {:kind {:account-payment {:filters [{:direction direction}]}}
     :bound {:kind {:max {:aggregate
                          {:kind {measure (utility/assoc-some
                                           {:value value :window period}
                                           :anchor anchor
                                           :starts-month starts-month
                                           :starts-day-of-month
                                           starts-day-of-month)}}}}}
     :reason (or reason (limit-reason currency payment-limit))}))

;; ---------------------------------------------------------------------------
;; Balance limits
;;
;; min-balance and max-balance, in minor units of the version's currency,
;; bound an account's available balance: a posting may not take it below
;; the minimum, zero where none is given, or above the maximum. A version
;; keeps each as a policy limit of the balance kind, on the transaction
;; types a customer or the bank drives, so interest is never refused, and
;; an account already outside a bound may move back towards it.

(def ^:private lowering-types
  "The transaction types a balance minimum bounds."
  [:transaction-type-internal-transfer :transaction-type-inbound-transfer
   :transaction-type-outbound-transfer :transaction-type-reward])

(def ^:private raising-types
  "The transaction types a balance maximum bounds."
  [:transaction-type-internal-transfer :transaction-type-inbound-transfer])

(defn- ensure-balance-limits
  [min-balance max-balance]
  (when (and max-balance (<= max-balance (or min-balance 0)))
    (error/reject :cash-account-product/invalid-limit
                  {:message "max-balance must be above min-balance"
                   :min-balance (or min-balance 0)
                   :max-balance max-balance})))

(defn- balance-limit
  [side value currency transaction-types reason]
  {:kind {:balance {:filters (mapv (fn [transaction-type]
                                     {:kind {:computed {:name "available"}}
                                      :transaction-type transaction-type})
                                   transaction-types)}}
   :bound {:kind {side {:aggregate {:kind {:amount
                                           {:value {:value value
                                                    :currency currency}
                                            :window :time-window-instant}}}}}}
   :reason reason
   :allow :limit-allow-improving})

(defn- balance-limits
  [currency min-balance max-balance]
  (let [minimum (or min-balance 0)]
    (cond-> [(balance-limit :min
                            minimum
                            currency
                            lowering-types
                            (str "The product's accounts hold at least "
                                 minimum
                                 " "
                                 currency
                                 " minor units"))]
            max-balance
            (conj (balance-limit :max
                                 max-balance
                                 currency
                                 raising-types
                                 (str "The product's accounts hold at most "
                                      max-balance
                                      " "
                                      currency
                                      " minor units"))))))

;; ---------------------------------------------------------------------------
;; Interest terms
;;
;; interest-terms, when present, is {:accrual :payment}: a grid of rates,
;; one for each period of an account's life and each band of its
;; balance, and when the accrued interest is paid. The template's entry
;; for the version's currency gives its day count and business day
;; convention. Counts, presence and a rate's sign are the API schema's;
;; the rules here are the ones no field can state.

(defn- strictly-increasing?
  "True where each of `xs` is above the one before."
  [xs]
  (or (empty? xs) (apply < xs)))

(defn- ascending-from?
  "True where `xs` starts at `origin` and each is above the one before."
  [origin xs]
  (and (= origin (first xs)) (strictly-increasing? xs)))

(defn- rate-for-every-band?
  "True where each of `periods` carries one rate for each of `bands`."
  [bands periods]
  (every? (fn [period] (= (count bands) (count (:rates-bps period))))
          periods))

(defn- period-starts
  "Each period's start in `schedule`, and where the first must start: 0
  after opening for a relative schedule, `effective-from` for a fixed
  one."
  [schedule effective-from]
  (if-some [relative (:relative schedule)]
    [(map :after (:periods relative)) 0]
    [(map :from-on (get-in schedule [:fixed :periods])) effective-from]))

(defn- accrual-problem
  [accrual effective-from]
  (let [{:keys [bands schedule]} accrual
        [starts origin] (period-starts schedule effective-from)
        periods (:periods (or (:relative schedule) (:fixed schedule)))]
    (cond
     (not (ascending-from? 0 bands))
     "Bands start at zero, each above the one before"

     (not (ascending-from? origin starts))
     (if (:relative schedule)
       "Periods start 0 after opening, each after the one before"
       "Periods start on the version's effective-from, each after the one before")

     (not (rate-for-every-band? bands periods))
     "Every period has one rate for each band"

     :else
     nil)))

(defn- flat-interest
  "Interest terms paying `rate-bps` on the whole balance from opening,
  paid daily. Nil at no rate or a zero one, since a version that pays
  nothing carries no terms."
  [rate-bps]
  (when (and rate-bps (not (zero? rate-bps)))
    {:accrual {:banding :interest-banding-marginal
               :bands [0]
               :schedule {:relative {:unit :interest-period-unit-month
                                     :periods [{:after 0
                                                :rates-bps [rate-bps]}]}}}
     :payment {:kind {:account-day {:unit :interest-period-unit-day
                                    :every 1}}}}))

(defn- interest-fields
  "The version's interest terms from the caller's `:interest-terms`, or
  its `:interest-rate-bps` as flat ones, with the day count and business
  day convention of the template's `currency-entry`. Nil where it pays
  none, or an anomaly where the terms cannot hold."
  [data currency-entry]
  (let [{:keys [interest-terms interest-rate-bps effective-from]} data
        terms (or interest-terms (flat-interest interest-rate-bps))
        problem (cond
                 (and (some? interest-terms) (some? interest-rate-bps))
                 "State interest-terms or interest-rate-bps, not both"

                 (nil? terms)
                 nil

                 :else
                 (accrual-problem (:accrual terms) effective-from))]
    (cond
     problem
     (error/reject :cash-account-product/invalid-interest-terms
                   {:message problem})

     terms
     (-> terms
         (assoc-in [:accrual :day-count] (:day-count currency-entry))
         (assoc-in [:payment :business-day-convention]
                   (:business-day-convention currency-entry)))

     :else
     nil)))

(defn- product-fields
  "Snapshot the derived instrument fields from the resolved `template`
  (product-type, balance-sheet-side, balance buckets, payment-address
  schemes, iso type) plus the caller's `:interest-terms`, or its
  `:interest-rate-bps` as flat ones, with the currency's conventions,
  `:opening-reward`, as an opening reward's terms, `:payment-limits`, as
  policy limits on the account's payments, and `:min-balance` and
  `:max-balance`, as policy limits on its available balance, and stamp
  the originating `:template-id` for provenance. Returns the fields or
  an anomaly when the caller's currency isn't allowed or a term cannot
  hold."
  [template data]
  (let [{:keys [currency opening-reward payment-limits min-balance
                max-balance]}
        data]
    (let-nom>
      [_ (ensure-currency-allowed template currency)
       interest-terms (interest-fields data
                                       (template-currency template currency))
       _ (ensure-payment-limits payment-limits)
       _ (ensure-balance-limits min-balance max-balance)]
      (utility/assoc-some
       {:product-type (:product-type template)
        :template-id (:template-id template)
        :balance-sheet-side (:balance-sheet-side template)
        :balance-products (:balance-products template)
        :allowed-payment-address-schemes
        (:allowed-payment-address-schemes template)
        :iso-cash-account-type (:iso-cash-account-type template)}
       :interest-terms
       interest-terms
       :reward-terms
       (when opening-reward
         [{:kind :reward-kind-opening :amount (:amount opening-reward)}])
       :limits
       (into (balance-limits currency min-balance max-balance)
             (map (fn [payment-limit]
                    (payment-limit->limit currency payment-limit)))
             payment-limits)))))

(defn new-template
  "Build a template record from seed data: stamp a stable `tpl.` id when
  the seed doesn't carry one, plus timestamps."
  [data]
  (let [now (utility/now)]
    (assoc data
           :template-id (or (:template-id data) (utility/generate-id "tpl"))
           :created-at now)))

;; ---------------------------------------------------------------------------
;; Capability + limit checks

(defn- check-capability
  "Checks `action` on a product of `product-type` by `actor`, whose kind
  a capability's filter may name, as the platform's denial of an
  own-funds product to anyone but the platform does."
  [action product-type actor policies]
  (policy/check-capability policies
                           :cash-account-product
                           (utility/assoc-some {:action action
                                                :product-type product-type}
                                               :created-by-kind
                                               (:kind actor))))

(defn- check-limit
  [aggregate window dimensions aggregates policies]
  (let [value (inc (get-in aggregates [:cash-account-product dimensions]))]
    (policy/check-limit policies
                        :cash-account-product
                        {:aggregate aggregate
                         :window window
                         :value value})))

(defn- check-product-type-limit
  "Per-product-type product cap. Passes `:product-type` so a policy limit
  whose filter enumerates that type matches; `:value` is the count of
  existing products of this type, +1. Internal types with no matching
  filter (own-funds) pass through."
  [product-type aggregates policies]
  (let [value (inc (get-in aggregates
                           [:cash-account-product #{:bank-id :product-type}]))]
    (policy/check-limit policies
                        :cash-account-product
                        {:aggregate :count
                         :window :time-window-instant
                         :product-type product-type
                         :value value})))

;; ---------------------------------------------------------------------------
;; Effective window
;;
;; effective-from / effective-to are epoch-day (int). effective-from is
;; required; effective-to, when present, must fall strictly after it.

(defn- ensure-effective-window
  [effective-from effective-to]
  (cond
   (nil? effective-from)
   (error/reject :cash-account-product/effective-from-required
                 {:message "A product needs an effective-from date"})

   (and effective-to (<= effective-to effective-from))
   (error/reject :cash-account-product/invalid-effective-window
                 {:message "effective-to must be after effective-from"
                  :effective-from effective-from
                  :effective-to effective-to})

   :else
   nil))

;; ---------------------------------------------------------------------------
;; Opening reward
;;
;; opening-reward, when present, is {:amount n} in minor units of the
;; version's currency, and n is positive: a version that promises nothing
;; carries no reward rather than a reward of nothing.

(defn- ensure-reward
  [opening-reward]
  (when (and (some? opening-reward)
             (not (pos? (or (:amount opening-reward) 0))))
    (error/reject :cash-account-product/invalid-reward
                  {:message "An opening reward must be a positive amount"
                   :opening-reward opening-reward})))

;; ---------------------------------------------------------------------------
;; Public domain operations

(defn new-version
  [bank-id product-id versions template data policies]
  (let [{:keys [name currency effective-from effective-to opening-reward]}
        data
        now (utility/now)]
    (let-nom>
      [_ (ensure-template-matches template data)
       fields (product-fields template data)
       _ (ensure-effective-window effective-from effective-to)
       _ (ensure-reward opening-reward)
       _ (check-capability :cash-account-product-action-draft
                           (:product-type template)
                           (:created-by data)
                           policies)
       _ (when (some draft? versions)
           (error/reject :cash-account-product/draft-already-exists
                         {:message "A draft already exists"
                          :bank-id bank-id
                          :product-id product-id}))]

      (utility/assoc-some
       (merge {:bank-id bank-id
               :product-id product-id
               :version-id (utility/generate-id "prv")
               :version-number (inc (count versions))
               :status :version-status-draft
               :name name
               :currency currency
               :created-at now
               :created-by (:created-by data)}
              fields)
       :effective-from effective-from
       :effective-to effective-to
       ;; The client's key on the create-product path (the command envelope
       ;; :id), unique-indexed so a replayed create reads the original
       ;; back. Only new-product stamps it; publish, discard and
       ;; update-version preserve it.
       :idempotency-key (:idempotency-key data)))))

(defn new-product
  [bank-id template data aggregates policies]
  (let-nom>
    [_ (check-limit :count
                    :time-window-instant
                    #{:bank-id}
                    aggregates
                    policies)
     _ (check-product-type-limit (:product-type template) aggregates policies)]
    (new-version bank-id
                 (utility/generate-id "prd")
                 []
                 template
                 data
                 policies)))

(defn update-version
  [existing template data policies actor]
  (let [{:keys [bank-id product-id version-id
                version-number status created-at created-by]}
        existing
        {:keys [name currency effective-from effective-to opening-reward]}
        data]
    (let-nom>
      [_ (ensure-draft existing)
       _ (ensure-template-matches template data)
       fields (product-fields template data)
       _ (ensure-effective-window effective-from effective-to)
       _ (ensure-reward opening-reward)
       _ (check-capability :cash-account-product-action-draft
                           (:product-type template)
                           actor
                           policies)]
      (utility/assoc-some
       (merge {:bank-id bank-id
               :product-id product-id
               :version-id version-id
               :version-number version-number
               :status status
               :name name
               :currency currency
               :created-at created-at
               :created-by created-by
               :updated-at (utility/now)
               :updated-by actor}
              fields)
       :effective-from effective-from
       :effective-to effective-to
       :idempotency-key (:idempotency-key existing)))))

(defn- check-address-schemes
  [version payment-provider]
  (when payment-provider
    (let [{:keys [addresses]} payment-provider
          issued (set (map (fn [scheme]
                             (keyword (str "payment-address-scheme-" scheme)))
                           addresses))
          unsupported (remove issued
                              (:allowed-payment-address-schemes version))]
      (when (seq unsupported)
        (error/reject :cash-account-product/unsupported-address-scheme
                      {:message (str "The payment provider does not issue "
                                     (str/join ", "
                                               (map
                                                (fn [scheme]
                                                  (str/replace
                                                   (name scheme)
                                                   #"^payment-address-scheme-"
                                                   ""))
                                                unsupported))
                                     " addresses")
                       :product-id (:product-id version)
                       :version-id (:version-id version)
                       :unsupported (vec unsupported)})))))

(defn publish
  [existing policies payment-provider actor]
  (let-nom>
    [_ (ensure-draft existing)
     _ (check-capability :cash-account-product-action-publish
                         (:product-type existing)
                         actor
                         policies)
     _ (check-address-schemes existing payment-provider)]
    (let [now (utility/now)]
      (assoc existing
             :status :version-status-published
             :updated-at now
             :updated-by actor))))

(defn discard
  [existing policies actor]
  (let-nom>
    [_ (ensure-draft existing)
     _ (check-capability :cash-account-product-action-draft
                         (:product-type existing)
                         actor
                         policies)]
    (let [now (utility/now)]
      (assoc existing
             :status :version-status-discarded
             :updated-at now
             :updated-by actor))))
