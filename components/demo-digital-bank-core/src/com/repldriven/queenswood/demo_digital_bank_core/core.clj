(ns com.repldriven.queenswood.demo-digital-bank-core.core
  (:require
    [com.repldriven.queenswood.demo-digital-bank-core.domain :as domain]
    [com.repldriven.queenswood.demo-digital-bank-core.platform :as platform]
    [com.repldriven.queenswood.demo-digital-bank-core.store :as store]

    [com.repldriven.mono.auth.interface :as auth]
    [com.repldriven.mono.encryption.interface :as encryption]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.utility.interface :as util]))

(def ^:private register-party "register-party")

(def ^:private open-account-kind "open-account")

(def ^:private opening-deposit-kind "opening-deposit")

(def ^:private submit-payment-kind "submit-payment")

(def ^:private transfer-kind "transfer")

(def ^:private opening-poll-ms 200)

(def ^:private opening-polls
  "How many times an account just opened is read back before its
  opening deposit is moved regardless: five seconds, against a watcher
  that takes well under one."
  25)

(defn- ds [bank] (get-in bank [:store :datasource]))

(defn- id [] (str (util/uuidv7)))

(defn- sign-up-view
  [sign-up]
  (select-keys sign-up [:id :status :party-id]))

(defn- fetch-sign-up
  [bank sign-up-id]
  (let-nom> [sign-up (store/sign-up-by-id (ds bank) sign-up-id)]
    (or sign-up
        (error/reject :sign-up/not-found
                      {:message "no such sign-up" :sign-up-id sign-up-id}))))

(defn start-sign-up
  [bank {:keys [phone]}]
  (let-nom> [sign-up (store/insert-sign-up (ds bank)
                                           {:id (id)
                                            :phone (domain/normalise-phone
                                                    phone)
                                            :status "code-sent"})]
    (sign-up-view sign-up)))

(defn verify-code
  [bank sign-up-id {:keys [code]}]
  (let-nom> [sign-up (fetch-sign-up bank sign-up-id)
             _ (domain/check-step sign-up :code)
             _ (domain/check-code sign-up code (:sign-up-code bank))
             updated (store/update-sign-up (ds bank)
                                           sign-up-id
                                           {:status "verified"})]
    (sign-up-view updated)))

(defn- submission
  "The registration's submission, minted before the platform is called
  so a repeat reuses the key and the platform recognises it."
  [bank sign-up registration]
  (let-nom> [existing (store/submission-for-sign-up (ds bank)
                                                    (:id sign-up)
                                                    register-party)]
    (or existing
        (let-nom> [request (json/write-str registration)]
          (store/insert-submission (ds bank)
                                   {:idempotency-key (id)
                                    :sign-up-id (:id sign-up)
                                    :kind register-party
                                    :request request})))))

(defn register-details
  "Register the person with the platform, and carry the party onto the
  sign-up."
  [bank sign-up-id details]
  (let-nom> [sign-up (fetch-sign-up bank sign-up-id)
             _ (domain/check-step sign-up :details)
             registration (domain/party-registration details)
             submitted (submission bank sign-up registration)
             party (platform/register-party (:platform bank)
                                            (:idempotency-key submitted)
                                            registration)
             answer (json/write-str party)
             _ (store/answer-submission (ds bank)
                                        (:idempotency-key submitted)
                                        answer)
             updated (store/update-sign-up (ds bank)
                                           sign-up-id
                                           {:status "registered"
                                            :party-id (:party-id party)
                                            :given-name (:given-name
                                                         details)
                                            :family-name (:family-name
                                                          details)})]
    (assoc (sign-up-view updated)
           :verification
           (:verification (domain/user updated party)))))

(defn- new-session
  [bank customer-id]
  (let-nom> [token (encryption/generate-token "ses")
             hashed (encryption/hash-token token)
             expires-at (+ (util/now) (* 1000 (:session-ttl-seconds bank)))
             row (store/insert-session (ds bank)
                                       {:id hashed
                                        :customer-id customer-id
                                        :expires-at expires-at})]
    {:token token :expires-at (:expires-at row)}))

(defn choose-passcode
  "Finish the sign-up: the customer, their passcode's hash and their
  first session, in one transaction."
  [bank sign-up-id {:keys [passcode]}]
  (let-nom> [sign-up (fetch-sign-up bank sign-up-id)
             _ (domain/check-step sign-up :passcode)
             hashed (auth/hash-password passcode)
             token (encryption/generate-token "ses")
             session-id (encryption/hash-token token)
             customer-id (id)
             expires-at (+ (util/now) (* 1000 (:session-ttl-seconds bank)))
             registered (store/register (ds bank)
                                        {:id customer-id
                                         :party-id (:party-id sign-up)
                                         :phone (:phone sign-up)
                                         :given-name (:given-name sign-up)
                                         :family-name (:family-name sign-up)
                                         :passcode-hash hashed}
                                        {:id session-id
                                         :expires-at expires-at})
             _ (store/update-sign-up (ds bank) sign-up-id {:status "done"})]
    {:token token
     :expires-at (str (java.time.Instant/ofEpochMilli expires-at))
     :customer-id (:customer-id registered)}))

(def ^:private credentials-invalid
  (error/unauthorized :sign-in/credentials-invalid
                      {:message "the phone number or passcode is wrong"}))

(defn sign-in
  "A session for a returning customer. The same refusal whether the
  number is unknown or the passcode is wrong."
  [bank {:keys [phone passcode]}]
  (let-nom> [customer (store/customer-by-phone (ds bank)
                                               (domain/normalise-phone
                                                phone))]
    (if (and customer
             (auth/verify-password passcode (:passcode-hash customer)))
      (new-session bank (:id customer))
      credentials-invalid)))

(def ^:private session-invalid
  (error/unauthorized :session/invalid
                      {:message "the session is missing or has expired"}))

(defn authenticate
  "The customer a session token resolves to, or an unauthorized anomaly."
  [bank token]
  (if (nil? token)
    session-invalid
    (let-nom> [hashed (encryption/hash-token token)
               row (store/live-session (ds bank) hashed)]
      (or (some-> row
                  (dissoc :session-id :expires-at)
                  (assoc :session-id (:session-id row)))
          session-invalid))))

(defn sign-out
  [bank token]
  (let-nom> [hashed (encryption/hash-token token)
             _ (store/delete-session (ds bank) hashed)]
    nil))

(defn accounts
  [bank customer]
  (store/accounts-by-customer (ds bank) (:id customer)))

(defn record-account
  "Record the account the bank opened for the customer."
  [bank customer account]
  (store/insert-account (ds bank) (assoc account :customer-id (:id customer))))

(defn customer-account
  "The account the customer holds under `account-id`, or a not-found
  rejection: an account the bank opened for anyone else is not there."
  [bank customer account-id]
  (let-nom> [held (accounts bank customer)]
    (or (some (fn [account]
                (when (= account-id (:account-id account)) account))
              held)
        (error/reject :account/not-found
                      {:message "no such account" :account-id account-id}))))

(defn- submitted-under
  "The customer's submission of `kind` under the key the app sent, or
  nil where it sent none or none is recorded."
  [bank customer kind client-key]
  (when client-key
    (store/submission-by-client-key (ds bank) (:id customer) kind client-key)))

(defn- answered
  "The platform's answer a submission carries, or nil while it has none."
  [submission]
  (some-> (:response submission)
          (json/read-str :key-fn keyword)))

(defn- submit
  "A submission to the platform under the key the app sent, where it
  sent one: the answer the platform already gave where there is one,
  otherwise the call under the idempotency key the first attempt
  minted. `call` takes that key and the request."
  [bank customer kind client-key request call]
  (let-nom> [existing (submitted-under bank customer kind client-key)]
    (if (:response existing)
      (answered existing)
      (let-nom> [submitted (or existing
                               (let-nom> [encoded (json/write-str request)]
                                 (store/insert-submission
                                  (ds bank)
                                  {:idempotency-key (id)
                                   :customer-id (:id customer)
                                   :client-key client-key
                                   :kind kind
                                   :request encoded})))
                 answer (call (:idempotency-key submitted) request)
                 encoded (json/write-str answer)
                 _ (store/answer-submission (ds bank)
                                            (:idempotency-key submitted)
                                            encoded)]
        answer))))

(defn check-payee
  "Ask the platform whether the payee's name is the one their bank
  holds."
  [bank _customer request]
  (let-nom> [check (platform/check-payee (:platform bank)
                                         (id)
                                         (domain/payee-check-request
                                          request))]
    (domain/payee-check-outcome check)))

(defn- resolve-payee
  "The payee the payment names: one of the customer's by id, or a new
  one from a name, sort code and account number."
  [bank customer payee]
  (if-let [payee-id (:id payee)]
    (let-nom> [row (store/payee-by-id (ds bank) (:id customer) payee-id)]
      (or row
          (error/reject :payee/not-found
                        {:message "no such payee" :payee-id payee-id})))
    (store/upsert-payee (ds bank)
                        {:id (id)
                         :customer-id (:id customer)
                         :name (:name payee)
                         :sort-code (domain/digits (:sort-code payee))
                         :account-number (domain/digits
                                          (:account-number payee))})))

(defn submit-payment
  "Pay a payee from one of the customer's accounts."
  [bank customer client-key {:keys [from payee amount reference]}]
  (let [client (:platform bank)]
    (let-nom> [_ (customer-account bank customer from)
               payee (resolve-payee bank customer payee)
               request (domain/outbound-payment-request from
                                                        payee
                                                        amount
                                                        reference)
               payment (submit bank
                               customer
                               submit-payment-kind
                               client-key
                               request
                               (fn [key request]
                                 (platform/submit-outbound-payment client
                                                                   key
                                                                   request)))
               paid (store/record-payee-payment (ds bank) (:id payee) amount)]
      (domain/payment payment (domain/payee paid)))))

(defn transfer
  "Move money between two of the customer's accounts."
  [bank customer client-key {:keys [from to amount reference]}]
  (let [client (:platform bank)]
    (let-nom> [_ (domain/check-transfer from to)
               _ (customer-account bank customer from)
               _ (customer-account bank customer to)
               request (domain/internal-payment-request from
                                                        to
                                                        amount
                                                        reference)
               payment (submit bank
                               customer
                               transfer-kind
                               client-key
                               request
                               (fn [key request]
                                 (platform/submit-internal-payment client
                                                                   key
                                                                   request)))]
      (domain/transfer payment))))

(defn- await-opened
  "The account read back with its balances once the platform reports
  it opened, or as it stands after the last poll."
  [client account-id]
  (loop [polls opening-polls]
    (let-nom> [account (platform/get-account client account-id)]
      (if (or (= "opened"
                 (some-> (:account-status account)
                         name))
              (zero? polls))
        account
        (do (Thread/sleep opening-poll-ms) (recur (dec polls)))))))

(defn open-account
  "Open an account against a product, record it as the customer's, and
  move the opening deposit from their current account where one is
  given. Answers `{:account :deposit}`, the account as the home read
  shows it and the deposit as a transfer, or nil where none was moved.
  A repeat under the app's key answers the account it opened rather
  than refusing a kind the customer now holds."
  [bank customer client-key {:keys [product-id name deposit]}]
  (let [client (:platform bank)
        today (util/today)]
    (let-nom> [listing (platform/list-products client)
               product (domain/find-product (domain/published-products
                                             listing)
                                            product-id)
               held (accounts bank customer)
               earlier (submitted-under bank
                                        customer
                                        open-account-kind
                                        client-key)
               replay (answered earlier)
               _ (if replay
                   product
                   (domain/check-account-open held product))
               source (domain/deposit-source (remove (fn [a]
                                                       (= (:account-id a)
                                                          (:account-id replay)))
                                                     held))
               deposit (if replay
                         (or deposit 0)
                         (domain/check-deposit product deposit source))
               request (domain/account-request (:party-id customer)
                                               product
                                               name)
               opened (submit bank
                              customer
                              open-account-kind
                              client-key
                              request
                              (fn [key request]
                                (platform/open-account client key request)))
               recorded (record-account bank
                                        customer
                                        {:account-id (:account-id opened)
                                         :product-kind (:kind product)
                                         :name (:name request)})
               ready (await-opened client (:account-id opened))
               moved (when (pos? deposit)
                       (let-nom> [payment (submit
                                           bank
                                           customer
                                           opening-deposit-kind
                                           client-key
                                           (domain/internal-payment-request
                                            (:account-id source)
                                            (:account-id opened)
                                            deposit
                                            (str "Opening " (:name request)))
                                           (fn [key request]
                                             (platform/submit-internal-payment
                                              client
                                              key
                                              request)))]
                         (domain/transfer payment)))
               platform-account (if moved
                                  (platform/get-account client
                                                        (:account-id opened))
                                  ready)
               legs (platform/list-transactions client (:account-id opened))]
      {:account (domain/account recorded
                                platform-account
                                (:transactions legs)
                                product
                                today)
       :deposit moved})))

(defn- payments
  "What the bank has paid out for the customer, from its submissions,
  in the shape the transactions listing names payees from."
  [bank customer]
  (let-nom> [rows (store/answered-submissions (ds bank)
                                              (:id customer)
                                              submit-payment-kind)]
    (into []
          (keep (fn [row]
                  (let [request (json/read-str (:request row) :key-fn keyword)
                        answer (json/read-str (:response row)
                                              :key-fn
                                              keyword)]
                    (when-not (or (error/anomaly? request)
                                  (error/anomaly? answer))
                      {:transaction-id (:transaction-id answer)
                       :account-id (:debtor-account-id request)
                       :amount (:amount request)
                       :reference (:reference request)
                       :name (:creditor-name request)
                       :created-at (:created-at answer)}))))
          rows)))

(defn me
  "Everything the app's home needs, read from the platform for this
  customer and no other."
  [bank customer]
  (let [client (:platform bank)
        today (util/today)]
    (let-nom> [party (platform/get-party client (:party-id customer))
               held (accounts bank customer)
               listing (platform/list-products client)
               products (domain/published-products listing)
               by-product (into {} (map (fn [p] [(:id p) p])) products)
               names (into {}
                           (map (fn [a] [(:account-id a) (:name a)]))
                           held)
               legs-by-account (reduce
                                (fn [m account]
                                  (let-nom> [legs (platform/list-transactions
                                                   client
                                                   (:account-id account))]
                                    (assoc m
                                           (:account-id account)
                                           (:transactions legs))))
                                {}
                                held)
               accounts (reduce
                         (fn [v account]
                           (let-nom> [platform-account (platform/get-account
                                                        client
                                                        (:account-id
                                                         account))]
                             (conj v
                                   (domain/account
                                    account
                                    platform-account
                                    (get legs-by-account (:account-id account))
                                    (get by-product
                                         (:product-id platform-account))
                                    today))))
                         []
                         held)
               payees (store/payees-by-customer (ds bank) (:id customer))
               paid (payments bank customer)]
      {:user (domain/user customer party)
       :accounts accounts
       :txns (domain/transactions legs-by-account names paid)
       :payees (mapv domain/payee payees)
       :products products})))
