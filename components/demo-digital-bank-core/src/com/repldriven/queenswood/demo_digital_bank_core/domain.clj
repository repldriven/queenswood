(ns com.repldriven.queenswood.demo-digital-bank-core.domain
  (:require
    [com.repldriven.mono.error.interface :as error]

    [clojure.string :as str]))

(def day-ms 86400000)

(def sparkline-days 7)

(def ^:private sign-up-transitions
  "The status a sign-up must be in for each step to advance it."
  {:code "code-sent" :details "verified" :passcode "registered"})

(defn normalise-phone
  "A UK mobile number in E.164: spaces and punctuation dropped, a
  leading 0 or a bare 44 made +44."
  [phone]
  (let [digits (str/replace (or phone "") #"[^0-9+]" "")]
    (cond (str/starts-with? digits "+")
          digits
          (str/starts-with? digits "00")
          (str "+" (subs digits 2))
          (str/starts-with? digits "0")
          (str "+44" (subs digits 1))
          (str/starts-with? digits "44")
          (str "+" digits)
          :else
          (str "+44" digits))))

(defn check-step
  "The sign-up when `step` may advance it, else an invalid-status
  rejection naming what was expected."
  [sign-up step]
  (let [expected (get sign-up-transitions step)]
    (if (= expected (:status sign-up))
      sign-up
      (error/reject :sign-up/invalid-status
                    {:message (str "sign-up is " (:status sign-up)
                                   ", not " expected)
                     :sign-up-id (:id sign-up)
                     :status (:status sign-up)
                     :allowed expected}))))

(defn check-code
  [sign-up code expected]
  (if (= code expected)
    sign-up
    (error/reject :sign-up/invalid-code {:message "the code does not match"})))

(defn party-registration
  "The platform's registration of a person, from what the sign-up
  screens collect. A UK bank's defaults fill what the screens leave out."
  [details]
  (let [{:keys [given-name family-name date-of-birth nationality address
                national-identifier]}
        details]
    {:type "person"
     :display-name (str given-name " " family-name)
     :given-name given-name
     :family-name family-name
     :date-of-birth date-of-birth
     :nationality (or nationality "GB")
     :address (merge {:country "GBR"} address)
     :national-identifier (merge {:type "national-insurance"
                                  :issuing-country "GB"}
                                 national-identifier)}))

(def ^:private product-kinds
  {"current" "cur" "savings" "sav" "term-deposit" "fix"})

(defn product-kind
  "The app's short kind for a platform product type."
  [product-type]
  (get product-kinds
       (some-> product-type
               name)
       "cur"))

(defn published-products
  "Each product's published version, as the app lists products."
  [listing]
  (into []
        (keep (fn [product]
                (when-let [version (some (fn [v]
                                           (when (= "published"
                                                    (some-> (:status v)
                                                            name))
                                             v))
                                         (:versions product))]
                  {:id (:product-id product)
                   :kind (product-kind (:product-type version))
                   :name (:name version)
                   :product-type (some-> (:product-type version)
                                         name)
                   :rate-bps (:interest-rate-bps version 0)})))
        (:items listing)))

(defn- rate-label
  [rate-bps]
  (format "%.2f%% AER" (/ rate-bps 100.0)))

(defn account-label
  "The line under an account's name: what kind it is and, where it
  earns, the rate."
  [kind name rate-bps]
  (case kind
    "sav" (str "Easy-access saver · " (rate-label rate-bps))
    "fix" (str name " · " (rate-label rate-bps))
    "Current account"))

(defn sort-code
  "A six-digit sort code as the app shows it, `04-00-75`."
  [digits]
  (if (and digits (= 6 (count digits)))
    (str/join "-" (re-seq #".." digits))
    digits))

(defn- scan-address
  [account]
  (some (fn [address]
          (when (= "scan"
                   (some-> (:scheme address)
                           name))
            (:scan address)))
        (:payment-addresses account)))

(defn- signed-amount
  [leg]
  (if (= "debit"
         (some-> (:side leg)
                 name))
    (- (:amount leg))
    (:amount leg)))

(defn- default-leg?
  [leg]
  (= "default"
     (some-> (:balance-type leg)
             name)))

(defn- parse-instant
  [rfc3339]
  (.toEpochMilli (java.time.Instant/parse rfc3339)))

(defn sparkline
  "The last seven daily closing balances of an account, oldest first,
  walked back from its posted balance through its posted legs.
  `today` is an epoch-day; legs carry `created-at` as RFC 3339."
  [posted legs today]
  (let [legs (filter (fn [leg]
                       (and (default-leg? leg)
                            (= "posted"
                               (some-> (:balance-status leg)
                                       name))))
                     legs)
        end-of-day (fn [day] (* day-ms (inc day)))]
    (mapv (fn [day]
            (let [cutoff (end-of-day day)
                  after (filter (fn [leg]
                                  (>= (parse-instant (:created-at leg))
                                      cutoff))
                                legs)]
              (- posted (reduce + 0 (map signed-amount after)))))
          (range (- today (dec sparkline-days)) (inc today)))))

(def ^:private categories
  {"outbound-transfer" "Payment"
   "inbound-transfer" "Received"
   "internal-transfer" "Saved"
   "interest-accrual" "Interest earned"
   "interest-capital" "Interest earned"
   "fee" "Fees"})

(defn- who
  "The other side of a leg, as far as the bank can tell: a transfer
  between the customer's own accounts names the other account, and
  anything else names its kind until the bank's own records say more."
  [leg by-transaction names]
  (let [type (some-> (:transaction-type leg)
                     name)]
    (case type
      "internal-transfer"
      (let [other (some (fn [other]
                          (when (not= (:leg-id other) (:leg-id leg))
                            (get names (:account-id other))))
                        (get by-transaction (:transaction-id leg)))]
        (str (if (= "debit"
                    (some-> (:side leg)
                            name))
               "Transfer to "
               "Transfer from ")
             (or other "another account")))
      "inbound-transfer" "Received"
      "outbound-transfer" "Payment"
      "fee" "Fee"
      "Interest")))

(defn transactions
  "The customer's legs across their accounts as the app lists them,
  newest first. `legs-by-account` maps an account id to its legs, and
  `names` an account id to the name the customer gave it."
  [legs-by-account names]
  (let [legs (filter default-leg? (apply concat (vals legs-by-account)))
        by-transaction (group-by :transaction-id legs)]
    (->> legs
         (map (fn [leg]
                {:id (:leg-id leg)
                 :acct (:account-id leg)
                 :who (who leg by-transaction names)
                 :cat (get categories
                           (some-> (:transaction-type leg)
                                   name)
                           "Other")
                 :amount (signed-amount leg)
                 :currency (:currency leg)
                 :status (some-> (:status leg)
                                 name)
                 :at (:created-at leg)
                 :ref (:reference leg)}))
         (sort-by :at)
         reverse
         vec)))

(defn account
  "One of the customer's accounts as the app shows it, from the
  platform's account with balances embedded, its legs, and the
  product it was opened against."
  [held platform-account legs product today]
  (let [{:keys [account-id product-kind]} held
        account-name (:name held)
        kind product-kind
        scan (scan-address platform-account)
        posted (get-in platform-account [:posted-balance :value] 0)
        available (get-in platform-account [:available-balance :value] posted)
        rate (:rate-bps product 0)]
    {:id account-id
     :kind kind
     :name account-name
     :type (account-label kind account-name rate)
     :status (some-> (:account-status platform-account)
                     name)
     :balance available
     :posted posted
     :currency (or (get-in platform-account [:available-balance :currency])
                   (:currency platform-account))
     :sort (sort-code (:sort-code scan))
     :num (:account-number scan)
     :spark (sparkline posted legs today)}))

(def ^:private verifications
  {"pending" "pending" "active" "verified" "rejected" "rejected"})

(defn user
  "The customer as the app shows them, with how far the platform's
  verification of their party has got."
  [customer party]
  {:first (:given-name customer)
   :last (:family-name customer)
   :phone (:phone customer)
   :verification (let [status (some-> (:status party)
                                      name)]
                   (get verifications status status))
   :member-since (:created-at party)})
