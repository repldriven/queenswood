(ns com.repldriven.queenswood.test-model.products
  (:require
    [com.repldriven.queenswood.test-model.state :as state]

    [clojure.test.check.generators :as gen]))

;; Epoch-days the runner's product verbs send: every create and
;; open-draft carries 20089 (2025-01-01), and an update moves the
;; window without letting it stop containing today.
(def ^:private default-effective-from 20089)
(def ^:private later-effective-from 20454)
(def ^:private far-effective-to 21184)

(defn version
  [status number]
  {:status status
   :number number
   :currency "GBP"
   :effective-from default-effective-from
   :effective-to nil})

(defn- new-product-state
  [bank-id product-type interest-rate-bps]
  {:bank bank-id
   :product-type product-type
   :interest-rate-bps interest-rate-bps
   :versions [(version :draft 1)]})

(def create-product
  {:run? (fn [state] (seq (state/known-banks state)))
   :args (fn [state]
           (gen/let [org (gen/elements (state/known-banks state))
                     type (gen/elements [:current :savings])
                     rate (gen/choose 100 10000)]
             [org type (if (= :savings type) rate 0)]))
   :next-state
   (fn [state {[bank-id type rate-bps] :args}]
     (let [prod-id (state/next-product-id state)]
       (-> state
           (assoc-in [:products prod-id]
                     (new-product-state bank-id type rate-bps))
           (update-in [:banks bank-id :products] (fnil conj []) prod-id)
           (update :next-product-id inc))))
   :valid? (fn [state {[bank-id] :args}] (contains? (:banks state) bank-id))})

(defn- flip-latest
  [state prod-id f]
  (update-in state
             [:products prod-id :versions]
             (fn [versions]
               (conj (pop versions) (f (peek versions))))))

(def publish-product
  {:run? (fn [state] (seq (state/drafts state)))
   :args (fn [state] (gen/tuple (gen/elements (state/drafts state))))
   :next-state
   (fn [state {[prod-id] :args}]
     (flip-latest state prod-id (fn [v] (assoc v :status :published))))
   :valid? (fn [state {[prod-id] :args}]
             (= :draft (:status (state/latest-version state prod-id))))})

(def discard-draft
  {:run? (fn [state] (seq (state/drafts state)))
   :args (fn [state] (gen/tuple (gen/elements (state/drafts state))))
   :next-state
   (fn [state {[prod-id] :args}]
     (flip-latest state prod-id (fn [v] (assoc v :status :discarded))))
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
   :next-state
   ;; Reality rejects an update whose target is unknown or no longer a
   ;; draft — predict a no-op, same convention as `close-account`.
   (fn [state {[prod-id data] :args}]
     (if (= :draft (:status (state/latest-version state prod-id)))
       (flip-latest state
                    prod-id
                    (fn [v]
                      (assoc v
                             :effective-from (:effective-from data)
                             :effective-to (:effective-to data))))
       state))
   :valid? (fn [state {[prod-id] :args}]
             (= :draft (:status (state/latest-version state prod-id))))})
