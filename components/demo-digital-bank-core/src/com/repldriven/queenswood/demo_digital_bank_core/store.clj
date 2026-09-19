(ns com.repldriven.queenswood.demo-digital-bank-core.store
  (:require
    [com.repldriven.mono.jdbc.interface :as jdbc]))

(def ^:private at
  "Timestamps are formatted in SQL, to RFC 3339 at microsecond precision,
  so what comes back is a string rather than whichever class pgjdbc picks."
  "to_char(%s at time zone 'utc', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')")

(defn- stamp [column] (format at column))

(def ^:private sign-up-columns
  (str "id, phone, status, party_id, given_name, family_name, "
       (stamp "created_at")
       " as created_at, "
       (stamp "updated_at")
       " as updated_at"))

(def ^:private customer-columns
  (str "id, party_id, phone, given_name, family_name, passcode_hash, "
       (stamp "created_at")
       " as created_at"))

(def ^:private account-columns
  (str "customer_id, account_id, product_kind, name, "
       (stamp "opened_at")
       " as opened_at"))

(def ^:private submission-columns
  (str "idempotency_key, sign_up_id, customer_id, client_key, kind, request,"
       " response, "
       (stamp "created_at")
       " as created_at"))

(def ^:private payee-columns
  (str "id, customer_id, name, sort_code, account_number, last_paid_amount, "
       (stamp "last_paid_at")
       " as last_paid_at, "
       (stamp "created_at")
       " as created_at"))

(defn insert-sign-up
  [ds {:keys [id phone status]}]
  (jdbc/execute-one! ds
                     [(str "insert into sign_ups (id, phone, status)"
                           " values (?, ?, ?) returning "
                           sign-up-columns) id phone status]))

(defn sign-up-by-id
  [ds id]
  (jdbc/execute-one! ds
                     [(str "select "
                           sign-up-columns
                           " from sign_ups where id = ?") id]))

(defn update-sign-up
  [ds id {:keys [status party-id given-name family-name]}]
  (jdbc/execute-one!
   ds
   [(str "update sign_ups set status = ?,"
         " party_id = coalesce(?, party_id),"
         " given_name = coalesce(?, given_name),"
         " family_name = coalesce(?, family_name),"
         " updated_at = now() where id = ? returning "
         sign-up-columns) status party-id given-name family-name id]))

(defn insert-customer
  [ds {:keys [id party-id phone given-name family-name passcode-hash]}]
  (jdbc/execute-one!
   ds
   [(str "insert into customers"
         " (id, party_id, phone, given_name, family_name, passcode_hash)"
         " values (?, ?, ?, ?, ?, ?) returning "
         customer-columns) id party-id phone given-name family-name
    passcode-hash]))

(defn customer-by-id
  [ds id]
  (jdbc/execute-one! ds
                     [(str "select "
                           customer-columns
                           " from customers where id = ?") id]))

(defn customer-by-phone
  [ds phone]
  (jdbc/execute-one! ds
                     [(str "select "
                           customer-columns
                           " from customers where phone = ?") phone]))

(defn insert-account
  "Record an account, or answer the row already there when the same
  account is recorded again."
  [ds {:keys [customer-id account-id product-kind name]}]
  (jdbc/execute-one!
   ds
   [(str "insert into customer_accounts"
         " (customer_id, account_id, product_kind, name)"
         " values (?, ?, ?, ?)"
         " on conflict (customer_id, account_id) do update set name = ?"
         " returning "
         account-columns) customer-id account-id product-kind name name]))

(defn accounts-by-customer
  [ds customer-id]
  (jdbc/execute! ds
                 [(str "select " account-columns
                       " from customer_accounts where customer_id = ?"
                       " order by opened_at") customer-id]))

(defn insert-session
  [ds {:keys [id customer-id expires-at]}]
  (jdbc/execute-one!
   ds
   [(str "insert into sessions (id, customer_id, expires_at)"
         " values (?, ?, to_timestamp(? / 1000.0)) returning id, "
         (stamp "expires_at")
         " as expires_at") id customer-id expires-at]))

(defn live-session
  "The session with this id while it has not expired, with its customer."
  [ds id]
  (jdbc/execute-one!
   ds
   [(str "select s.id as session_id, "
         (stamp "s.expires_at")
         " as expires_at, c.id, c.party_id, c.phone, c.given_name,"
         " c.family_name, "
         (stamp "c.created_at")
         " as created_at from sessions s join customers c"
         " on c.id = s.customer_id where s.id = ? and s.expires_at > now()")
    id]))

(defn delete-session
  [ds id]
  (jdbc/execute-one! ds ["delete from sessions where id = ?" id]))

(defn submission-for-sign-up
  [ds sign-up-id kind]
  (jdbc/execute-one!
   ds
   [(str "select "
         submission-columns
         " from submissions where sign_up_id = ? and kind = ?") sign-up-id
    kind]))

(defn submission-by-client-key
  [ds customer-id kind client-key]
  (jdbc/execute-one!
   ds
   [(str "select "
         submission-columns
         " from submissions where customer_id = ? and kind = ?"
         " and client_key = ?") customer-id kind client-key]))

(defn answered-submissions
  "The customer's submissions of `kind` the platform answered, oldest
  first."
  [ds customer-id kind]
  (jdbc/execute!
   ds
   [(str "select "
         submission-columns
         " from submissions where customer_id = ? and kind = ?"
         " and response is not null order by created_at") customer-id kind]))

(defn insert-submission
  [ds {:keys [idempotency-key sign-up-id customer-id client-key kind request]}]
  (jdbc/execute-one!
   ds
   [(str "insert into submissions"
         " (idempotency_key, sign_up_id, customer_id, client_key, kind,"
         " request) values (?, ?, ?, ?, ?, ?) returning "
         submission-columns) idempotency-key sign-up-id customer-id client-key
    kind request]))

(defn answer-submission
  [ds idempotency-key response]
  (jdbc/execute-one!
   ds
   [(str "update submissions set response = ?, updated_at = now()"
         " where idempotency_key = ? returning "
         submission-columns) response idempotency-key]))

(defn register
  "The customer a finished sign-up becomes, with its first session, in
  one statement: both rows or neither."
  [ds customer session]
  (let [{:keys [id party-id phone given-name family-name passcode-hash]}
        customer]
    (jdbc/execute-one!
     ds
     [(str "with c as (insert into customers"
           " (id, party_id, phone, given_name, family_name, passcode_hash)"
           " values (?, ?, ?, ?, ?, ?) returning id)"
           " insert into sessions (id, customer_id, expires_at)"
           " select ?, c.id, to_timestamp(? / 1000.0) from c"
           " returning customer_id") id party-id phone given-name family-name
      passcode-hash (:id session) (:expires-at session)])))

(defn upsert-payee
  "The customer's payee at this sort code and account number, created
  under `id` or renamed to `name` where one is already there."
  [ds {:keys [id customer-id name sort-code account-number]}]
  (jdbc/execute-one!
   ds
   [(str "insert into payees"
         " (id, customer_id, name, sort_code, account_number)"
         " values (?, ?, ?, ?, ?)"
         " on conflict (customer_id, sort_code, account_number)"
         " do update set name = ? returning "
         payee-columns) id customer-id name sort-code account-number name]))

(defn payee-by-id
  [ds customer-id id]
  (jdbc/execute-one!
   ds
   [(str "select "
         payee-columns
         " from payees where customer_id = ? and id = ?") customer-id id]))

(defn payees-by-customer
  "The customer's payees, most recently paid first."
  [ds customer-id]
  (jdbc/execute!
   ds
   [(str "select "
         payee-columns
         " from payees where customer_id = ?"
         " order by last_paid_at desc nulls last, created_at desc")
    customer-id]))

(defn record-payee-payment
  [ds id amount]
  (jdbc/execute-one!
   ds
   [(str "update payees set last_paid_at = now(), last_paid_amount = ?"
         " where id = ? returning "
         payee-columns) amount id]))
