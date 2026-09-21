(ns com.repldriven.queenswood.demo-digital-bank.domain
  (:require
    [com.repldriven.mono.error.interface :as error]

    [clojure.string :as str])
  (:import
    (java.nio.charset StandardCharsets)
    (java.security MessageDigest)
    (java.util Base64)
    (javax.crypto Mac)
    (javax.crypto.spec SecretKeySpec)))

(def day-ms 86400000)

(def sparkline-days 7)

(def currency "GBP")

(def fixed-term-minimum
  "The least a fixed-term account opens with, in minor units, until
  the platform carries a minimum deposit on the product."
  100000)

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

(defn digits
  "Only the digits of a sort code or account number as typed."
  [s]
  (str/replace (or s "") #"[^0-9]" ""))

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

(defn find-product
  "The published product with this id, or a not-found rejection."
  [products product-id]
  (or (some (fn [p] (when (= product-id (:id p)) p)) products)
      (error/reject :product/not-found
                    {:message "no such product" :product-id product-id})))

(defn check-account-open
  "The product when the customer holds no account of its kind, else a
  rejection: the app opens one of each."
  [held product]
  (if (some (fn [a] (= (:kind product) (:product-kind a))) held)
    (error/reject :account/kind-held
                  {:message (str "you already have a " (:name product))
                   :product-id (:id product)})
    product))

(defn deposit-source
  "The account an opening deposit is moved from: the customer's current
  account, or nil when they hold none."
  [held]
  (some (fn [a] (when (= "cur" (:product-kind a)) a)) held))

(defn check-deposit
  "The deposit when it may be moved, else a rejection: a fixed-term
  account opens with at least the minimum, and any deposit needs a
  current account to come from."
  [product deposit source]
  (let [deposit (or deposit 0)]
    (cond (and (= "fix" (:kind product)) (< deposit fixed-term-minimum))
          (error/reject :deposit/below-minimum
                        {:message (str (:name product)
                                       " opens with at least "
                                       (quot fixed-term-minimum 100)
                                       " pounds")
                         :minimum fixed-term-minimum})
          (and (pos? deposit) (nil? source))
          (error/reject :deposit/no-source-account
                        {:message "open an Everyday account first to fund"})
          :else
          deposit)))

(defn account-request
  "The platform's request to open an account for the party."
  [party-id product name]
  {:party-id party-id
   :name (or name (:name product))
   :currency currency
   :product-id (:id product)})

(defn payee-check-request
  [{:keys [name sort-code account-number]}]
  {:creditor-name name
   :account {:sort-code (digits sort-code)
             :account-number (digits account-number)}
   :account-type "personal"})

(def ^:private outcomes
  {"match" "match" "close-match" "close-match" "no-match" "no-match"})

(defn payee-check-outcome
  "What the payee's bank said, in the app's words: `match`,
  `close-match` with the name held, `no-match`, or `unavailable`."
  [check]
  (let [{:keys [match-result actual-name]} (:result check)
        held (some-> actual-name
                     str/trim)]
    {:check-id (:check-id check)
     :outcome (get outcomes
                   (some-> match-result
                           name)
                   "unavailable")
     :name-held (when-not (str/blank? held) held)}))

(defn check-transfer
  [from to]
  (if (= from to)
    (error/reject :transfer/same-account
                  {:message "choose two different accounts"})
    to))

(defn- with-reference
  [request reference]
  (if (str/blank? reference)
    request
    (assoc request :reference (str/trim reference))))

(defn outbound-payment-request
  "The platform's Faster Payment out of the customer's account to the
  payee."
  [account-id payee amount reference]
  (with-reference {:debtor-account-id account-id
                   :creditor-bban (str (:sort-code payee)
                                       (:account-number payee))
                   :creditor-name (:name payee)
                   :currency currency
                   :amount amount
                   :scheme "fps"}
                  reference))

(defn internal-payment-request
  [from to amount reference]
  (with-reference {:debtor-account-id from
                   :creditor-account-id to
                   :currency currency
                   :amount amount}
                  reference))

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

(defn payee
  "A payee as the app lists them, with when and how much they were
  last paid."
  [row]
  {:id (:id row)
   :name (:name row)
   :sort (sort-code (:sort-code row))
   :num (:account-number row)
   :last-paid-at (:last-paid-at row)
   :last-paid-amount (:last-paid-amount row)})

(defn payment
  "An outbound payment as the app shows it once sent."
  [platform-payment payee]
  {:id (:payment-id platform-payment)
   :status (some-> (:payment-status platform-payment)
                   name)
   :from (:debtor-account-id platform-payment)
   :payee payee
   :amount (:amount platform-payment)
   :currency (:currency platform-payment)
   :reference (:reference platform-payment)
   :created-at (:created-at platform-payment)})

(defn transfer
  "A transfer between the customer's own accounts as the app shows it."
  [platform-payment]
  {:id (:payment-id platform-payment)
   :from (:debtor-account-id platform-payment)
   :to (:creditor-account-id platform-payment)
   :amount (:amount platform-payment)
   :currency (:currency platform-payment)
   :reference (:reference platform-payment)
   :created-at (:created-at platform-payment)})

(defn- scan-address
  [account]
  (some (fn [address]
          (when (= "scan"
                   (some-> (:scheme address)
                           name))
            (:scan address)))
        (:payment-addresses account)))

(defn- named
  [leg k v]
  (= v
     (some-> (get leg k)
             name)))

(defn- debit? [leg] (named leg :side "debit"))

(defn- signed-amount
  [leg]
  (if (debit? leg) (- (:amount leg)) (:amount leg)))

(defn- default-leg? [leg] (named leg :balance-type "default"))

(defn- posted? [leg] (named leg :balance-status "posted"))

(defn- pending-outgoing? [leg] (named leg :balance-status "pending-outgoing"))

(defn- parse-instant
  [rfc3339]
  (.toEpochMilli (java.time.Instant/parse rfc3339)))

(defn sparkline
  "The last seven daily closing balances of an account, oldest first,
  walked back from its posted balance through its posted legs.
  `today` is an epoch-day; legs carry `created-at` as RFC 3339."
  [posted legs today]
  (let [legs (filter (fn [leg] (and (default-leg? leg) (posted? leg))) legs)
        end-of-day (fn [day] (* day-ms (inc day)))]
    (mapv (fn [day]
            (let [cutoff (end-of-day day)
                  after (filter (fn [leg]
                                  (>= (parse-instant (:created-at leg))
                                      cutoff))
                                legs)]
              (- posted (reduce + 0 (map signed-amount after)))))
          (range (- today (dec sparkline-days)) (inc today)))))

(defn- outstanding
  "The money set aside for payments still in flight: each
  pending-outgoing credit releases the earliest debit of its amount
  still standing, and what is left is what the customer sees as sent."
  [legs]
  (:open
   (reduce (fn [acc leg]
             (if (debit? leg)
               (update acc :open conj leg)
               (if-let [released (some (fn [open]
                                         (when (= (:amount open)
                                                  (:amount leg))
                                           open))
                                       (:open acc))]
                 (update acc
                         :open
                         (fn [open]
                           (vec (remove (fn [o]
                                          (= (:leg-id o)
                                             (:leg-id released)))
                                        open))))
                 acc)))
           {:open []}
           (sort-by :created-at (filter pending-outgoing? legs)))))

(def ^:private categories
  {"outbound-transfer" "Payment"
   "inbound-transfer" "Received"
   "internal-transfer" "Saved"
   "interest-accrual" "Interest earned"
   "interest-capital" "Interest earned"
   "reward" "Rewards"
   "fee" "Fees"})

(defn- same-reference?
  "Whether two references read the same, a missing one and a blank one
  alike."
  [a b]
  (= (str/trim (or a "")) (str/trim (or b ""))))

(defn- payee-of
  "The payee an outbound leg was to, from the bank's own payments: the
  one the leg's transaction belongs to, else the latest before it out
  of the same account for the same amount and reference."
  [leg payments]
  (or (some (fn [p]
              (when (= (:transaction-id p) (:transaction-id leg)) (:name p)))
            payments)
      (->> payments
           (filter (fn [p]
                     (and (= (:account-id p) (:account-id leg))
                          (= (:amount p) (:amount leg))
                          (same-reference? (:reference p) (:reference leg))
                          (or (nil? (:created-at p))
                              (<= (parse-instant (:created-at p))
                                  (parse-instant (:created-at leg)))))))
           (sort-by :created-at)
           last
           :name)))

(defn- who
  "The other side of a leg, as far as the bank can tell: a transfer
  between the customer's own accounts names the other account, a
  payment names the payee where the bank made it, a reward names what
  it was for, and anything else names its kind."
  [leg by-transaction names payments]
  (let [type (some-> (:transaction-type leg)
                     name)]
    (case type
      "internal-transfer"
      (let [other (some (fn [other]
                          (when (not= (:leg-id other) (:leg-id leg))
                            (get names (:account-id other))))
                        (get by-transaction (:transaction-id leg)))]
        (str (if (debit? leg) "Transfer to " "Transfer from ")
             (or other "another account")))
      "inbound-transfer" "Received"
      "outbound-transfer" (or (payee-of leg payments) "Payment")
      "fee" "Fee"
      "reward" "Welcome reward"
      "Interest")))

(defn transactions
  "The customer's legs across their accounts as the app lists them,
  newest first: what has posted, with the status `posted`, and what is
  set aside for a payment still in flight, with the status `pending` —
  the leg's own balance status, never its transaction's, which an
  outbound payment's settlement leaves reading pending. `legs-by-account`
  maps an account id to its legs, `names` an account id to the name
  the customer gave it, and `payments` is what the bank submitted —
  `{:transaction-id :account-id :amount :reference :name :created-at}`
  each."
  [legs-by-account names payments]
  (let [legs (filter default-leg? (apply concat (vals legs-by-account)))
        posted (filter posted? legs)
        pending (mapcat (fn [[_ account-legs]]
                          (outstanding (filter default-leg? account-legs)))
                 legs-by-account)
        by-transaction (group-by :transaction-id posted)]
    (->> (concat (map (fn [leg] [leg "posted"]) posted)
                 (map (fn [leg] [leg "pending"]) pending))
         (map (fn [[leg status]]
                {:id (:leg-id leg)
                 :acct (:account-id leg)
                 :who (who leg by-transaction names payments)
                 :cat (get categories
                           (some-> (:transaction-type leg)
                                   name)
                           "Other")
                 :amount (signed-amount leg)
                 :currency (:currency leg)
                 :status status
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

(def signature-tolerance-ms
  "How far a delivery's timestamp may sit from now, either way, before
  it is refused as a replay: five minutes, the Standard Webhooks
  recommendation."
  300000)

(def ^:private secret-prefix "whsec_")

(def ^:private scheme-version "v1")

(def ^:private hmac-algorithm "HmacSHA256")

(defn- key-bytes
  "The secret's key material: base64 after the prefix, in either
  alphabet, since the platform mints it URL-safe and unpadded."
  [secret]
  (-> secret
      (str/replace-first (re-pattern (str "^" secret-prefix)) "")
      (str/replace "-" "+")
      (str/replace "_" "/")
      (->> (.decode (Base64/getDecoder)))))

(defn- signed-content
  "The bytes a signature covers: the message id, the timestamp in
  seconds and the body as delivered, joined by dots."
  [message-id timestamp ^bytes body]
  (let [prefix (.getBytes (str message-id "." timestamp ".")
                          StandardCharsets/UTF_8)
        out (byte-array (+ (alength prefix) (alength body)))]
    (System/arraycopy prefix 0 out 0 (alength prefix))
    (System/arraycopy body 0 out (alength prefix) (alength body))
    out))

(defn sign
  "The `v1,<base64>` signature of a delivery under `secret`, or a
  `:webhook/signing` anomaly when the secret is not a key this can
  read."
  [secret message-id timestamp body]
  (error/try-nom
   :webhook/signing
   "the webhook secret is not a key"
   (let [mac (doto (Mac/getInstance hmac-algorithm)
               (.init (SecretKeySpec. (key-bytes secret) hmac-algorithm)))
         digest (.doFinal mac (signed-content message-id timestamp body))]
     (str scheme-version "," (.encodeToString (Base64/getEncoder) digest)))))

(defn- unverified
  [kind message]
  (error/unauthorized kind {:message message}))

(defn- signatures
  "The signatures the header carries, space-separated: two inside a
  rotation window, one otherwise."
  [header]
  (remove str/blank? (str/split (or header "") #"\s+")))

(defn verify-delivery
  "The message id and timestamp of a delivery whose Standard Webhooks
  headers verify under `secret`, or an unauthorized anomaly: the
  signature is over the id, the timestamp and the body as delivered,
  the timestamp may not sit further than the tolerance from `now`, and
  any one of the signatures the header carries matching is enough,
  which is what lets the platform rotate the secret."
  [secret headers ^bytes body now]
  (let [message-id (get headers "webhook-id")
        timestamp (get headers "webhook-timestamp")
        seconds (some-> timestamp
                        parse-long)]
    (cond (str/blank? secret)
          (unverified :webhook/no-secret "the bank holds no webhook secret")

          (or (str/blank? message-id) (nil? seconds))
          (unverified :webhook/unsigned "the delivery carries no signature")

          (> (abs (- now (* 1000 seconds))) signature-tolerance-ms)
          (unverified :webhook/stale "the delivery's timestamp is too old")

          :else
          (let [expected (sign secret message-id timestamp body)]
            (cond (error/anomaly? expected)
                  expected

                  (some (fn [given]
                          (MessageDigest/isEqual
                           (.getBytes ^String given StandardCharsets/UTF_8)
                           (.getBytes ^String expected
                                      StandardCharsets/UTF_8)))
                        (signatures (get headers "webhook-signature")))
                  {:message-id message-id :timestamp seconds}

                  :else
                  (unverified :webhook/invalid-signature
                              "the delivery's signature does not verify"))))))

(defn notification
  "What the delivered envelope names, or a rejection where it names no
  notification: `{:id :kind :resource-type :resource-id :data}`."
  [delivery]
  (let [{:keys [notification-id kind resource-type resource-id data]}
        delivery]
    (if (or (str/blank? notification-id) (str/blank? kind))
      (error/reject :webhook/malformed
                    {:message "the delivery names no notification"})
      {:id notification-id
       :kind kind
       :resource-type resource-type
       :resource-id resource-id
       :data data})))

(defn notified-account
  "The account a notification is about, or nil for a kind that names
  none: the account opened, the one a payment credits — the customer
  who sent one already holds that answer, until the scheme changes
  it — or the one a reward was paid to."
  [kind record]
  (case kind
    "cash-account.opened" (:account-id record)
    ("payment.internal-settled" "payment.inbound-status-changed")
    (:creditor-account-id record)
    "payment.outbound-status-changed" (:debtor-account-id record)
    "reward.paid" (:account-id record)
    nil))

(defn- money
  "An amount in minor units as the customer reads it: `£50.00`, or the
  currency's code before the figure for any other."
  [{:keys [amount currency]}]
  (let [figure (format "%d.%02d" (quot amount 100) (rem amount 100))]
    (if (= "GBP" currency) (str "£" figure) (str currency " " figure))))

(defn notification-view
  "A notification as the app is told it: `{:id :kind :at :headline
  :detail :account}`, the headline in the customer's words for the
  kinds the bank knows and a plain statement of the change for the
  rest. A payment's sender is never named where it is the bank."
  [row record]
  (let [{:keys [id kind received-at]} row
        base {:id id
              :kind kind
              :at received-at
              :detail nil
              :account (notified-account kind record)}
        unknown (assoc base :headline (str "Something changed: " kind))
        from (some->> (:debtor-name record)
                      (str "From "))]
    (case kind
      "cash-account.opened"
      (assoc base
             :headline (str (or (:name record) "Your account") " is open")
             :detail "Your new account is ready to use.")
      "payment.internal-settled"
      (assoc base
             :headline (str (money record) " arrived")
             :detail (:reference record))
      "payment.inbound-status-changed"
      (case (:payment-status record)
        "settled"
        (assoc base :headline (str (money record) " arrived") :detail from)
        ("held" "suspended") (assoc base
                                    :headline (str (money record) " is on hold")
                                    :detail from)
        "returned" (assoc base
                          :headline (str (money record) " was returned")
                          :detail from)
        unknown)
      "payment.outbound-status-changed"
      (let [to (str "Payment to " (or (:creditor-name record) "your payee"))
            sent (str (money record)
                      (some->> (:reference record)
                               (str ", ")))]
        (case (:payment-status record)
          "completed" (assoc base :headline (str to " sent") :detail sent)
          "failed" (assoc base :headline (str to " failed") :detail sent)
          "held" (assoc base :headline (str to " is held") :detail sent)
          unknown))
      "reward.paid"
      (assoc base
             :headline (str (money record) " welcome reward arrived")
             :detail "Paid for opening your account.")
      unknown)))
