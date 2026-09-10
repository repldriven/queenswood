(ns com.repldriven.queenswood.cash-account-product.domain
  (:require
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- draft?
  [version]
  (= :cash-account-product-status-draft (:status version)))

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

(defn- ensure-currency-allowed
  [template currency]
  (when-not (contains? (set (:allowed-currencies template)) currency)
    (error/reject :cash-account-product/currency-not-allowed
                  {:message "Currency not allowed for this template"
                   :currency currency
                   :allowed-currencies (:allowed-currencies template)})))

(defn- product-fields
  "Snapshot the derived instrument fields from the resolved `template`
  (product-type, balance-sheet-side, balance buckets, payment-address
  schemes, iso type) plus the caller's `:interest-rate-bps`, and stamp
  the originating `:template-id` for provenance. Returns the fields or
  an anomaly when the caller's currency isn't allowed."
  [template data]
  (let [{:keys [currency interest-rate-bps]} data]
    (let-nom>
      [_ (ensure-currency-allowed template currency)]
      (utility/assoc-some
       {:product-type (:product-type template)
        :template-id (:template-id template)
        :balance-sheet-side (:balance-sheet-side template)
        :balance-products (:balance-products template)
        :allowed-payment-address-schemes
        (:allowed-payment-address-schemes template)
        :interest-rate-bps (or interest-rate-bps 0)}
       :iso-cash-account-type
       (:iso-cash-account-type template)
       :internal
       (when (:internal template) true)))))

(defn new-template
  "Build a template record from seed data: stamp a stable `tpl.` id when
  the seed doesn't carry one, plus timestamps."
  [data]
  (let [now (utility/now)]
    (assoc data
           :template-id (or (:template-id data) (utility/generate-id "tpl"))
           :created-at now
           :updated-at now)))

;; ---------------------------------------------------------------------------
;; Capability + limit checks

(defn- check-capability
  [action product-type policies]
  (policy/check-capability policies
                           :cash-account-product
                           {:action action :product-type product-type}))

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
;; Public domain operations

(defn new-version
  [bank-id product-id versions template data policies]
  (let [{:keys [name currency effective-from effective-to]} data
        now (utility/now)]
    (let-nom>
      [fields (product-fields template data)
       _ (ensure-effective-window effective-from effective-to)
       _ (check-capability :cash-account-product-action-draft
                           (:product-type template)
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
               :status :cash-account-product-status-draft
               :name name
               :allowed-currencies [currency]
               :created-at now
               :updated-at now}
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
  [existing template data policies]
  (let [{:keys [bank-id product-id version-id
                version-number status created-at]}
        existing
        {:keys [name currency effective-from effective-to]} data]
    (let-nom>
      [_ (ensure-draft existing)
       fields (product-fields template data)
       _ (ensure-effective-window effective-from effective-to)
       _ (check-capability :cash-account-product-action-draft
                           (:product-type template)
                           policies)]
      (utility/assoc-some
       (merge {:bank-id bank-id
               :product-id product-id
               :version-id version-id
               :version-number version-number
               :status status
               :name name
               :allowed-currencies [currency]
               :created-at created-at
               :updated-at (utility/now)}
              fields)
       :effective-from effective-from
       :effective-to effective-to
       :idempotency-key (:idempotency-key existing)))))

(defn publish
  [existing policies]
  (let-nom>
    [_ (ensure-draft existing)
     _ (check-capability :cash-account-product-action-publish
                         (:product-type existing)
                         policies)]
    (assoc existing
           :status :cash-account-product-status-published
           :updated-at (utility/now))))

(defn discard
  [existing policies]
  (let-nom>
    [_ (ensure-draft existing)
     _ (check-capability :cash-account-product-action-draft
                         (:product-type existing)
                         policies)]
    (let [now (utility/now)]
      (assoc existing
             :status :cash-account-product-status-discarded
             :discarded-at now
             :updated-at now))))
