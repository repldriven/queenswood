(ns com.repldriven.queenswood.demo-digital-bank.store
  (:require
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.jdbc.interface :as jdbc]
    [com.repldriven.mono.json.interface :as json])
  (:import
    (java.sql Timestamp)
    (java.time ZoneOffset)
    (java.time.format DateTimeFormatter)))

(def ^:private rfc3339
  (.withZone (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
             ZoneOffset/UTC))

(def ^:private json-columns #{:request :response :record})

(defn- read-value
  "A column as the bank reads it: a timestamp as RFC 3339 at the
  microsecond precision Postgres keeps, and a JSON column as data, keys
  as keywords, or the anomaly in its place where it does not read."
  [k v]
  (cond (instance? Timestamp v)
        (.format ^DateTimeFormatter rfc3339 (.toInstant ^Timestamp v))

        (and (some? v) (contains? json-columns k))
        (json/read-str v :key-fn keyword)

        :else
        v))

(defn- read-row
  [row]
  (when row
    (reduce-kv (fn [m k v] (assoc m k (read-value k v))) {} row)))

(defn- timestamp [epoch-ms] (Timestamp. epoch-ms))

(defn insert-sign-up
  [ds sign-up]
  (error/nom-> (jdbc/insert! ds :sign-ups sign-up) read-row))

(defn sign-up-by-id
  [ds id]
  (error/nom-> (jdbc/get-by-id ds :sign-ups id) read-row))

(defn update-sign-up
  [ds {:keys [id status party-id given-name family-name]}]
  (error/nom->
   (jdbc/execute-one!
    ds
    ["update sign_ups set status = ?, party_id = ?, given_name = ?,
      family_name = ?, updated_at = now() where id = ? returning *"
     status party-id given-name family-name id])
   read-row))

(defn customer-by-party-id
  [ds party-id]
  (error/nom-> (jdbc/find-by-keys ds :customers {:party-id party-id})
               first
               read-row))

(defn customer-by-phone
  [ds phone]
  (error/nom-> (jdbc/find-by-keys ds :customers {:phone phone}) first read-row))

(defn customer-by-account-id
  "The customer the bank opened `account-id` for, or nil where it
  opened it for nobody."
  [ds account-id]
  (error/nom->
   (jdbc/execute-one!
    ds
    ["select * from customers where id =
      (select customer_id from customer_accounts where account_id = ?)"
     account-id])
   read-row))

(defn insert-account
  "Record an account, or answer the row already there when the same
  account is recorded again."
  [ds {:keys [customer-id account-id product-kind name]}]
  (error/nom->
   (jdbc/execute-one!
    ds
    ["insert into customer_accounts
      (customer_id, account_id, product_kind, name) values (?, ?, ?, ?)
      on conflict (customer_id, account_id) do update set name = ?
      returning *"
     customer-id account-id product-kind name name])
   read-row))

(defn accounts-by-customer
  [ds customer-id]
  (error/nom->> (jdbc/find-by-keys ds
                                   :customer-accounts
                                   {:customer-id customer-id}
                                   {:order-by [:opened-at]})
                (mapv read-row)))

(defn insert-session
  [ds session]
  (error/nom->
   (jdbc/insert! ds :sessions (update session :expires-at timestamp))
   read-row))

(defn live-session
  "The session with this id while it has not expired, with its customer."
  [ds id]
  (error/nom->
   (jdbc/execute-one!
    ds
    ["select s.id as session_id, s.expires_at, c.id, c.party_id, c.phone,
      c.given_name, c.family_name, c.created_at
      from sessions s join customers c on c.id = s.customer_id
      where s.id = ? and s.expires_at > now()"
     id])
   read-row))

(defn delete-session [ds id] (jdbc/delete! ds :sessions {:id id}))

(defn submission-for-sign-up
  [ds sign-up-id kind]
  (error/nom-> (jdbc/find-by-keys ds
                                  :submissions
                                  {:sign-up-id sign-up-id :kind kind})
               first
               read-row))

(defn submission-by-client-key
  [ds customer-id kind client-key]
  (error/nom-> (jdbc/find-by-keys ds
                                  :submissions
                                  {:customer-id customer-id
                                   :kind kind
                                   :client-key client-key})
               first
               read-row))

(defn answered-submissions
  "The customer's submissions of `kind` the platform answered, oldest
  first."
  [ds customer-id kind]
  (error/nom->>
   (jdbc/execute!
    ds
    ["select * from submissions where customer_id = ? and kind = ?
      and response is not null order by created_at"
     customer-id kind])
   (mapv read-row)))

(defn insert-submission
  [ds submission]
  (let-nom> [request (json/write-str (:request submission))]
    (error/nom->
     (jdbc/insert! ds :submissions (assoc submission :request request))
     read-row)))

(defn answer-submission
  [ds {:keys [idempotency-key response]}]
  (let-nom> [encoded (json/write-str response)]
    (error/nom->
     (jdbc/execute-one!
      ds
      ["update submissions set response = ?, updated_at = now()
        where idempotency_key = ? returning *"
       encoded idempotency-key])
     read-row)))

(defn register
  "The customer a finished sign-up becomes, with its first session, in
  one statement: both rows or neither."
  [ds customer session]
  (let [{:keys [id party-id phone given-name family-name passcode-hash]}
        customer]
    (jdbc/execute-one!
     ds
     ["with c as (insert into customers
         (id, party_id, phone, given_name, family_name, passcode_hash)
         values (?, ?, ?, ?, ?, ?) returning id)
       insert into sessions (id, customer_id, expires_at)
       select ?, c.id, ? from c returning customer_id"
      id party-id phone given-name family-name passcode-hash (:id session)
      (timestamp (:expires-at session))])))

(defn upsert-payee
  "The customer's payee at this sort code and account number, created
  under `id` or renamed to `name` where one is already there."
  [ds {:keys [id customer-id name sort-code account-number]}]
  (error/nom->
   (jdbc/execute-one!
    ds
    ["insert into payees
      (id, customer_id, name, sort_code, account_number)
      values (?, ?, ?, ?, ?)
      on conflict (customer_id, sort_code, account_number)
      do update set name = ? returning *"
     id customer-id name sort-code account-number name])
   read-row))

(defn payee-by-id
  [ds customer-id id]
  (error/nom-> (jdbc/find-by-keys ds :payees {:customer-id customer-id :id id})
               first
               read-row))

(defn payees-by-customer
  "The customer's payees, most recently paid first."
  [ds customer-id]
  (error/nom->>
   (jdbc/execute!
    ds
    ["select * from payees where customer_id = ?
      order by last_paid_at desc nulls last, created_at desc"
     customer-id])
   (mapv read-row)))

(defn record-payee-payment
  [ds {:keys [id last-paid-amount]}]
  (error/nom->
   (jdbc/execute-one!
    ds
    ["update payees set last_paid_at = now(), last_paid_amount = ?
      where id = ? returning *"
     last-paid-amount id])
   read-row))

(defn record-notification
  "Record a delivery as it arrived, answering the row when there is
  work to do on it and nil when there is not: a notification id never
  seen is inserted, one recorded but never resolved takes this delivery
  and is answered again, and one already resolved to its customer is
  left as it is."
  [ds {:keys [id delivery-id kind body]}]
  (error/nom->
   (jdbc/execute-one!
    ds
    ["insert into notifications (id, delivery_id, kind, body)
      values (?, ?, ?, ?)
      on conflict (id) do update
      set delivery_id = excluded.delivery_id, received_at = now()
      where notifications.resolved_at is null returning *"
     id delivery-id kind body])
   read-row))

(defn resolve-notification
  [ds {:keys [id customer-id record]}]
  (let-nom> [encoded (json/write-str record)]
    (error/nom->
     (jdbc/execute-one!
      ds
      ["update notifications set customer_id = ?, record = ?,
        resolved_at = now() where id = ? returning *"
       customer-id encoded id])
     read-row)))

(defn unseen-notifications
  "The customer's notifications not yet shown to them, oldest first."
  [ds customer-id]
  (error/nom->> (jdbc/find-by-keys ds
                                   :notifications
                                   {:customer-id customer-id :seen-at nil}
                                   {:order-by [:received-at]})
                (mapv read-row)))

(defn mark-seen
  [ds id]
  (jdbc/execute-one! ds
                     ["update notifications set seen_at = now() where id = ?"
                      id]))
