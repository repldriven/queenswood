(ns com.repldriven.queenswood.demo-digital-bank.core
  (:require
    [com.repldriven.queenswood.demo-digital-bank.domain :as domain]
    [com.repldriven.queenswood.demo-digital-bank.platform :as platform]
    [com.repldriven.queenswood.demo-digital-bank.store :as store]

    [com.repldriven.mono.auth.interface :as auth]
    [com.repldriven.mono.encryption.interface :as encryption]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.sse.interface :as sse]
    [com.repldriven.mono.utility.interface :as util])
  (:import
    (java.nio.charset StandardCharsets)))

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

(defn- fetch-sign-up
  [bank sign-up-id]
  (let-nom> [sign-up (store/sign-up-by-id (ds bank) sign-up-id)]
    (or sign-up
        (error/reject :sign-up/not-found
                      {:message "no such sign-up" :sign-up-id sign-up-id}))))

(defn start-sign-up
  [bank {:keys [phone]}]
  (let-nom> [sign-up (store/insert-sign-up (ds bank)
                                           (domain/new-sign-up phone))]
    (domain/sign-up-view sign-up)))

(defn verify-code
  [bank sign-up-id {:keys [code]}]
  (let-nom> [sign-up (fetch-sign-up bank sign-up-id)
             verified (domain/verify sign-up code (:sign-up-code bank))
             updated (store/update-sign-up (ds bank) verified)]
    (domain/sign-up-view updated)))

(defn- submission
  "The registration's submission, minted before the platform is called
  so a repeat reuses the key and the platform recognises it."
  [bank sign-up registration]
  (let-nom> [existing (store/submission-for-sign-up (ds bank)
                                                    (:id sign-up)
                                                    register-party)]
    (or existing
        (store/insert-submission (ds bank)
                                 (domain/sign-up-submission sign-up
                                                            register-party
                                                            registration)))))

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
             _ (store/answer-submission (ds bank)
                                        (domain/answered submitted party))
             updated (store/update-sign-up (ds bank)
                                           (domain/registered sign-up
                                                              details
                                                              party))]
    (assoc (domain/sign-up-view updated)
           :verification
           (:verification (domain/user updated party)))))

(defn- new-session
  [bank customer-id]
  (let-nom> [token (encryption/generate-token "ses")
             hashed (encryption/hash-token token)
             row (store/insert-session (ds bank)
                                       (domain/new-session
                                        customer-id
                                        hashed
                                        (:session-ttl-seconds bank)))]
    {:token token :expires-at (:expires-at row)}))

(defn choose-passcode
  "Finish the sign-up: the customer, their passcode's hash and their
  first session, in one transaction."
  [bank sign-up-id {:keys [passcode]}]
  (let-nom> [sign-up (fetch-sign-up bank sign-up-id)
             _ (domain/check-step sign-up :passcode)
             hashed (auth/hash-password passcode)
             token (encryption/generate-token "ses")
             token-hash (encryption/hash-token token)
             customer (domain/new-customer sign-up hashed)
             session (domain/new-session (:id customer)
                                         token-hash
                                         (:session-ttl-seconds bank))
             registered (store/register (ds bank) customer session)
             _ (store/update-sign-up (ds bank) (domain/finished sign-up))]
    {:token token
     :expires-at (str (java.time.Instant/ofEpochMilli (:expires-at session)))
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
  (store/insert-account (ds bank) (domain/held-account customer account)))

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

(defn- submit
  "A submission to the platform under the key the app sent, where it
  sent one: the answer the platform already gave where there is one,
  otherwise the call under the idempotency key the first attempt
  minted. `call` takes that key and the request."
  [bank customer kind client-key request call]
  (let-nom> [existing (submitted-under bank customer kind client-key)]
    (if (:response existing)
      (:response existing)
      (let-nom> [submitted (or existing
                               (store/insert-submission
                                (ds bank)
                                (domain/customer-submission customer
                                                            kind
                                                            client-key
                                                            request)))
                 answer (call (:idempotency-key submitted) request)
                 _ (store/answer-submission (ds bank)
                                            (domain/answered submitted
                                                             answer))]
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
    (store/upsert-payee (ds bank) (domain/new-payee customer payee))))

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
               paid (store/record-payee-payment (ds bank)
                                                (domain/paid payee amount))]
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
               replay (:response earlier)
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
               recorded (store/insert-account (ds bank)
                                              (domain/opened-account
                                               customer
                                               opened
                                               product
                                               request))
               ready (await-opened client (:account-id opened))
               moved (when (pos? deposit)
                       (let-nom> [payment (submit
                                           bank
                                           customer
                                           opening-deposit-kind
                                           client-key
                                           (domain/opening-deposit-request
                                            source
                                            opened
                                            deposit
                                            request)
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
                                legs
                                product
                                today)
       :deposit moved})))

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
                                           legs)))
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
               submitted (store/answered-submissions (ds bank)
                                                     (:id customer)
                                                     submit-payment-kind)
               paid (domain/payments submitted)]
      {:user (domain/user customer party)
       :accounts accounts
       :txns (domain/transactions legs-by-account names paid)
       :payees (mapv domain/payee payees)
       :products products})))

(defn- read-back
  "The record a notification is about, read from the platform for the
  kinds whose resource the bank can read, and as delivered otherwise."
  [client kind {:keys [resource-id data]}]
  (case kind
    "cash-account.opened" (platform/get-account client resource-id)
    data))

(defn- customer-of
  "The customer a record belongs to, or nil where it belongs to none:
  an account is a party's, and a party is one customer. A payment or a
  reward names the account it lands on, which is a customer's or
  nobody's — the house account, when the bank funds itself."
  [bank kind record]
  (case kind
    "cash-account.opened" (store/customer-by-party-id (ds bank)
                                                      (:party-id record))
    (some->> (domain/notified-account kind record)
             (store/customer-by-account-id (ds bank)))))

(defn- tell
  "Read the record back, write the notification against the customer
  it belongs to, and push it on their stream. Runs after the delivery
  was answered, so a failure here is logged and the row is left
  unresolved, to be taken again when the platform sends it again."
  [bank row notification]
  (let [kind (:kind row)
        result (let-nom> [record (read-back (:platform bank)
                                            kind
                                            notification)
                          customer (customer-of bank kind record)
                          resolved (store/resolve-notification
                                    (ds bank)
                                    (domain/resolved row customer record))]
                 (when customer
                   (sse/publish! (:streams bank)
                                 (:id customer)
                                 (domain/notification-view resolved record)))
                 resolved)]
    (when (error/anomaly? result)
      (log/error (str "notification " (:id row)
                      " not resolved: "
                      (:message (error/payload result)))))))

(defn receive
  "Take a delivery from the platform: verify it, record it under its
  notification id, and answer before doing anything else. Answers
  `{:notification-id :status}`, the status `accepted` where there is
  work to do on it and `done` where the notification was already
  resolved."
  [bank headers body]
  (let-nom> [verified (domain/verify-delivery (:webhook-secret bank)
                                              headers
                                              body
                                              (util/now))
             text (String. ^bytes body StandardCharsets/UTF_8)
             delivery (json/read-str text :key-fn keyword)
             notification (domain/notification delivery)
             row (store/record-notification (ds bank)
                                            (domain/received notification
                                                             verified
                                                             text))]
    (when row (future (tell bank row notification)))
    {:notification-id (:id notification) :status (if row "accepted" "done")}))

(defn- unseen
  "What the customer has not been shown, oldest first, as they are told
  it."
  [bank customer]
  (let-nom> [rows (store/unseen-notifications (ds bank) (:id customer))]
    (mapv (fn [row] (domain/notification-view row (:record row))) rows)))

(defn open-streams
  [bank customer]
  (sse/open-count (:streams bank) (:id customer)))

(defn events
  "Hold the customer's event stream open: what they have not been shown
  first, then each notification as it is recorded, each marked shown
  once emitted."
  [bank customer emit]
  (sse/serve (:streams bank)
             (:id customer)
             emit
             {:keep-alive-ms (:keep-alive-ms bank)
              :pending (fn [_] (unseen bank customer))
              :on-sent (fn [event] (store/mark-seen (ds bank) (:id event)))}))
