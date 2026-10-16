(ns com.repldriven.queenswood.test-model.products
  (:require
    [com.repldriven.queenswood.test-model.policies :as policies]
    [com.repldriven.queenswood.test-model.state :as state]

    [clojure.test.check.generators :as gen]))

(def
  ^{:private true
    :doc
    "The effective-from the runner's product verbs send, 2025-01-01
  as an epoch day, so a published version is in force when an account
  opens."}
  default-effective-from
  20089)

(def ^:private later-effective-from 20454)

(def ^:private far-effective-to 21184)

(defn version
  [status number]
  {:status status
   :number number
   :currency "GBP"
   :effective-from default-effective-from
   :effective-to nil})

(defn- flat-terms
  "Interest terms paying `rate-bps` on the whole balance from opening, as
  a version states a flat rate. Nil at a zero rate, which pays none."
  [rate-bps]
  (when-not (zero? rate-bps)
    {:banding :marginal
     :bands [0]
     :schedule {:relative {:unit :month
                           :periods [{:after 0 :rates-bps [rate-bps]}]}}}))

(defn- new-product-state
  [bank-id product-type interest]
  (let [terms (if (map? interest) interest (flat-terms interest))]
    {:bank bank-id
     :product-type product-type
     :versions [(cond-> (version :draft 1)

                        terms
                        (assoc :terms terms))]}))

(def create-product
  {:run? (fn [state] (seq (state/known-banks state)))
   :args (fn [state]
           (gen/let [org (gen/elements (state/known-banks state))
                     type (gen/elements [:current :savings])
                     rate (gen/choose 100 10000)]
             [org type (if (= :savings type) rate 0)]))
   :next-state
   (fn [state {[bank-id type interest] :args}]
     (let [prod-id (state/next-product-id state)]
       (-> state
           (assoc-in [:products prod-id]
                     (new-product-state bank-id type interest))
           (update-in [:banks bank-id :products] (fnil conj []) prod-id)
           (update :next-product-id inc))))
   :valid? (fn [state {[bank-id] :args}] (contains? (:banks state) bank-id))})

(defn- flip-latest
  [state prod-id f]
  (update-in state
             [:products prod-id :versions]
             (fn [versions]
               (conj (pop versions) (f (peek versions))))))

(defn- within-publishes?
  "Whether the bank's policies allow `prod-id` one more publish this
  month: every publish in a run falls in the month it runs in, so each
  published version counts."
  [state prod-id]
  (policies/within-count?
   state
   (get-in state [:products prod-id :bank])
   :cash-account-product
   :cash-account-product-action-publish
   :time-window-monthly
   (count (filter (fn [v] (= :published (:status v)))
                  (get-in state [:products prod-id :versions])))))

(def publish-product
  {:run? (fn [state] (seq (state/drafts state)))
   :args (fn [state] (gen/tuple (gen/elements (state/drafts state))))
   :next-state
   (fn [state {[prod-id] :args}]
     (if (within-publishes? state prod-id)
       (flip-latest state prod-id (fn [v] (assoc v :status :published)))
       state))
   :valid? (fn [state {[prod-id] :args}]
             (= :draft (:status (state/latest-version state prod-id))))})

(defn- remove-product
  [state prod-id]
  (let [bank-id (get-in state [:products prod-id :bank])]
    (-> state
        (update :products dissoc prod-id)
        (update-in [:banks bank-id :products]
                   (fn [prod-ids]
                     (filterv (fn [id] (not= prod-id id)) prod-ids))))))

(def discard-draft
  {:run? (fn [state] (seq (state/drafts state)))
   :args (fn [state] (gen/tuple (gen/elements (state/drafts state))))
   :next-state (fn [state {[prod-id] :args}]
                 (let [state
                       (update-in state [:products prod-id :versions] pop)]
                   (cond-> state
                           (empty? (get-in state [:products prod-id :versions]))
                           (remove-product prod-id))))
   :valid? (fn [state {[prod-id] :args}]
             (= :draft (:status (state/latest-version state prod-id))))})

(def open-draft
  {:run? (fn [state] (seq (state/open-draftable state)))
   :args (fn [state] (gen/tuple (gen/elements (state/open-draftable state))))
   :next-state (fn [state {[prod-id] :args}]
                 (let [latest (state/latest-version state prod-id)]
                   (update-in state
                              [:products prod-id :versions]
                              conj
                              (version :draft (inc (:number latest))))))
   :valid? (fn [state {[prod-id] :args}]
             (let [latest (state/latest-version state prod-id)]
               (and latest (not= :draft (:status latest)))))})

(def update-product-draft
  {:run? (fn [state] (seq (state/drafts state)))
   :args (fn [state]
           (gen/let [prod-id (gen/elements (state/drafts state))
                     from (gen/elements [default-effective-from
                                         later-effective-from])
                     to (gen/elements [nil far-effective-to])]
             [prod-id {:effective-from from :effective-to to}]))
   :next-state (fn [state {[prod-id data] :args}]
                 (if (= :draft (:status (state/latest-version state prod-id)))
                   (flip-latest state
                                prod-id
                                (fn [v]
                                  (-> v
                                      (assoc :effective-from (:effective-from
                                                              data)
                                             :effective-to (:effective-to data))
                                      (dissoc :terms))))
                   state))
   :valid? (fn [state {[prod-id] :args}]
             (= :draft (:status (state/latest-version state prod-id))))})
