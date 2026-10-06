(ns com.repldriven.queenswood.modulr-simulator.ledger
  "The simulator's accounts, balances and payments, held in one atom and
  changed under its lock so a debit checks and takes a balance at once,
  with each account's payments waiting for funds in the order they
  arrived.
  An account the simulator does not know — one it issued before a
  restart — is served as an active account with no balance to check."
  (:require
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str])
  (:import
    (java.math BigDecimal RoundingMode)
    (java.time Instant ZoneOffset)
    (java.time.format DateTimeFormatter)))

(def ^:private ^DateTimeFormatter timestamp-format
  (.withZone (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssZ")
             ZoneOffset/UTC))

(defn timestamp
  []
  (.format timestamp-format (Instant/ofEpochMilli (utility/now))))

(defn amount
  ^BigDecimal [x]
  (.setScale (bigdec (if (string? x) (BigDecimal. ^String x) x))
             2
             RoundingMode/HALF_EVEN))

(defn amount-str
  [^BigDecimal a]
  (.toPlainString (.setScale a 2 RoundingMode/HALF_EVEN)))

(defn- id
  [prefix]
  (str prefix (str/upper-case (str/replace (str (utility/uuidv7)) "-" ""))))

(def ^:private account-numbers
  "How many eight-digit account numbers there are."
  100000000)

(defn- account-number
  "The first number from `next` up that `taken?` refuses, and the one
  after it."
  [next taken?]
  (loop [n next]
    (let [number (format "%08d" n)
          following (mod (inc n) account-numbers)]
      (if (taken? number) (recur following) [number following]))))

(defn empty-state
  "A simulator with nothing open. Account numbers are issued in turn
  from the clock's milliseconds when it starts, which run ahead of any
  rate accounts are opened at, so a restarted simulator issues none an
  earlier one did until the numbers wrap, about every 28 hours."
  []
  {:next-number (mod (utility/now) account-numbers)
   :accounts {}
   :scan {}
   :payments {}
   :waiting {}
   :nonces {}
   :notifications {}
   :refuse-next false
   :refuse-next-close false})

;; ---- accounts

(defn- unknown-account
  [account-id]
  {:id account-id
   :balance (amount 0)
   :currency "GBP"
   :status "ACTIVE"
   :unbounded true})

(defn account
  [state account-id]
  (or (get-in @state [:accounts account-id])
      (let [a (unknown-account account-id)]
        (swap! state assoc-in [:accounts account-id] a)
        a)))

(defn account-by-scan
  [state sort-code account-number]
  (when-let [account-id (get-in @state [:scan (str sort-code account-number)])]
    (get-in @state [:accounts account-id])))

(defn open-account
  "Open an account under `customer-id`, or `{:refused message}` when a
  control route asked for the next opening to be refused."
  [state customer-id sort-code {:keys [currency externalReference]}]
  (locking state
    (if (:refuse-next @state)
      (do (swap! state assoc :refuse-next false)
          {:refused "The account was declined"})
      (let [{:keys [scan next-number]} @state
            [number following] (account-number next-number
                                               (fn [n]
                                                 (contains? scan
                                                            (str sort-code
                                                                 n))))
            a {:id (id "A")
               :customer-id customer-id
               :currency (or currency "GBP")
               :balance (amount 0)
               :status "ACTIVE"
               :sort-code sort-code
               :account-number number
               :external-reference externalReference
               :created-date (timestamp)}]
        (swap! state
          (fn [s]
            (-> s
                (assoc :next-number following)
                (assoc-in [:accounts (:id a)] a)
                (assoc-in [:scan (str sort-code number)] (:id a)))))
        a))))

(defn account-response
  [{:keys [id customer-id currency balance status sort-code account-number
           external-reference created-date]}]
  (utility/assoc-some {:id id
                       :balance (amount-str balance)
                       :availableBalance (amount-str balance)
                       :currency currency
                       :status status
                       :identifiers (if sort-code
                                      [{:type "SCAN"
                                        :sortCode sort-code
                                        :accountNumber account-number}]
                                      [])}
                      :customerId customer-id
                      :externalReference external-reference
                      :createdDate created-date))

(defn set-status
  [state account-id status]
  (account state account-id)
  (swap! state assoc-in [:accounts account-id :status] status))

(defn close-account
  "Close the account, or `{:refused message}` while it holds money or
  when a control route asked for the next close to be refused."
  [state account-id]
  (locking state
    (let [{:keys [balance unbounded]} (account state account-id)]
      (cond
       (:refuse-next-close @state)
       (do (swap! state assoc :refuse-next-close false)
           {:refused "The account could not be closed"})

       (and (not unbounded) (pos? (.signum ^BigDecimal balance)))
       {:refused "The account's balance is not zero"}

       :else
       (do (set-status state account-id "CLOSED") {:closed account-id})))))

(defn credit
  [state account-id amt]
  (locking state
    (account state account-id)
    (swap! state update-in [:accounts account-id :balance] + (amount amt))))

(defn debit
  "Take `amt` from the account, or false where it holds less."
  [state account-id amt]
  (locking state
    (let [{:keys [balance unbounded]} (account state account-id)
          amt (amount amt)]
      (if (or unbounded (>= (.compareTo ^BigDecimal balance amt) 0))
        (do (swap! state update-in [:accounts account-id :balance] - amt) true)
        false))))

;; ---- payments

(defn new-payment
  [state payment]
  (let [p (merge {:id (id "P") :createdDate (timestamp)} payment)]
    (swap! state assoc-in [:payments (:id p)] p)
    p))

(defn payment
  [state payment-id]
  (get-in @state [:payments payment-id]))

(defn update-payment
  [state payment-id f]
  (get-in (swap! state update-in [:payments payment-id] f)
          [:payments payment-id]))

(defn find-payments
  [state {:keys [id externalReference schemeId]}]
  (->> (vals (:payments @state))
       (filter (fn [p]
                 (and (or (nil? id) (= id (:id p)))
                      (or (nil? externalReference)
                          (= externalReference (:externalReference p)))
                      (or (nil? schemeId) (= schemeId (:schemeId p))))))
       (sort-by :createdDate)
       vec))

(defn payment-response
  [p]
  (select-keys p
               [:id :status :type :externalReference :schemeId :createdDate
                :details :message]))

(defn waiting
  [state account-id]
  (get-in @state [:waiting account-id] []))

(defn wait-for-funds
  [state account-id payment-id]
  (swap! state
    (fn [s]
      (-> s
          (update-in [:waiting account-id] (fnil conj []) payment-id)
          (assoc-in [:payments payment-id :status] "PENDING_FOR_FUNDS")))))

(defn stop-waiting
  [state account-id payment-id]
  (swap! state
    update-in
    [:waiting account-id]
    (fn [ids] (filterv (fn [id] (not= id payment-id)) ids))))

;; ---- nonces

(defn seen-nonce
  [state nonce]
  (get-in @state [:nonces nonce]))

(defn remember-nonce
  [state nonce response]
  (swap! state assoc-in [:nonces nonce] response))

;; ---- notifications

(defn register-notification
  [state customer-id {:keys [type] :as body}]
  (let [registration (assoc (select-keys body
                                         [:url :retry :secret :hmacAlgorithm
                                          :type])
                            :id
                            (id "W"))]
    (swap! state assoc-in [:notifications customer-id type] registration)
    registration))

(defn notifications
  [state customer-id]
  (vals (get-in @state [:notifications customer-id])))

(defn notification
  [state customer-id type]
  (get-in @state [:notifications customer-id type]))
