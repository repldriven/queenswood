(ns com.repldriven.queenswood.test-model.state)


(def init-state
  {:accounts {}
   :banks {}
   :products {}
   :parties {}
   :payments {}
   :inbound-payments #{}
   :nis-by-bank {}
   :platform-policies []
   :policies {}
   :next-id 0
   :next-bank-id 0
   :next-product-id 0
   :next-party-id 0
   :next-payment-id 0
   :next-inbound-id 0
   :next-ni-id 0
   ;; A fresh date for every generated run: the platform allows one run
   ;; of each kind per date.
   :next-interest-date 20260501
   :now 0})

(defn next-id
  [state]
  ;; Not `:acct/<n>`: the reader refuses a namespaced keyword whose name
  ;; starts with a digit.
  (keyword (str "acct-" (:next-id state))))

(defn next-bank-id
  [state]
  (keyword (str "bank-" (:next-bank-id state))))

(defn next-product-id
  [state]
  (keyword (str "prod-" (:next-product-id state))))

(defn next-party-id
  [state]
  (keyword (str "party-" (:next-party-id state))))

(defn next-payment-id
  [state]
  (keyword (str "pmt-" (:next-payment-id state))))

(defn next-inbound-id
  [state]
  (keyword (str "in-" (:next-inbound-id state))))

(defn next-ni-id
  [state]
  (keyword (str "ni-" (:next-ni-id state))))

(defn next-interest-date
  [state]
  (:next-interest-date state))

(defn known-accounts
  [state]
  (vec (keys (:accounts state))))

(defn open-accounts
  [state]
  (vec (for [[acct-id a] (:accounts state)
             :when (= :open (:status a))]
         acct-id)))

(defn known-banks
  [state]
  (vec (keys (:banks state))))

(defn latest-version
  [state prod-id]
  (peek (get-in state [:products prod-id :versions])))

(defn has-published-version?
  [state prod-id]
  (boolean (some (fn [v] (= :published (:status v)))
                 (get-in state [:products prod-id :versions]))))

(defn drafts
  [state]
  (vec (for [[prod-id _] (:products state)
             :when (= :draft (:status (latest-version state prod-id)))]
         prod-id)))

(defn open-draftable
  [state]
  (vec (for [[prod-id _] (:products state)
             :let [latest (latest-version state prod-id)]
             :when (and latest (not= :draft (:status latest)))]
         prod-id)))

(defn balance
  [state acct]
  (get-in state [:accounts acct :available] 0))
