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
                         held)]
      {:user (domain/user customer party)
       :accounts accounts
       :txns (domain/transactions legs-by-account names)
       :payees []
       :products products})))
