(ns com.repldriven.queenswood.demo-digital-bank-core.interface
  "The demo digital bank: the records a product built on Queenswood
  keeps for itself, and the line it draws between a customer and the
  platform. A customer signs up in steps — a phone number, a code, their
  details, a passcode — and the details register a person with the
  platform under the organisation's credential. The bank mints its own
  sessions; the platform's identity server plays no part. Every read
  resolves a session to a customer, the customer to a party and the
  accounts the bank opened for it, and nothing else the credential can
  reach is served.

  Every operation takes the `bank` instance first — the started
  `demo-digital-bank-core/bank` component, carrying the store, the
  platform client and the sign-up code — and returns a value or an
  anomaly, never a throw. A step taken out of order is a
  `:sign-up/invalid-status` rejection carrying `:status` and
  `:allowed`; a wrong code is `:sign-up/invalid-code`; an unknown
  sign-up, account, payee or product is `:sign-up/not-found`,
  `:account/not-found`, `:payee/not-found` or `:product/not-found`; a
  failed sign-in or a dead session is an unauthorized anomaly; the
  bank's own rules refuse as `:account/kind-held`,
  `:deposit/below-minimum`, `:deposit/no-source-account` and
  `:transfer/same-account`; and a refusal from the platform is
  `:platform/refused`, carrying the problem details it answered with.

  A submission — a payment, a transfer, an account opened — takes the
  key the app sent with it, or nil. Under a key the bank has seen the
  platform's answer to, it answers the same again; under one it minted
  an idempotency key for but has no answer to, it calls the platform
  under that same key, so a repeated tap pays once.

  Requiring this namespace registers the brick's system component
  kinds: `store`, `platform` and `bank`."
  (:require
    [com.repldriven.queenswood.demo-digital-bank-core.system]

    [com.repldriven.queenswood.demo-digital-bank-core.core :as core]))

(defn start-sign-up
  "Open a sign-up for a phone number, answering `{:id :status}` with
  the code sent.

  Args:
  - bank: the started bank component.
  - request: `{:phone}`, a UK mobile number in any usual spelling."
  [bank request]
  (core/start-sign-up bank request))

(defn verify-code
  "Advance a sign-up past its code, answering `{:id :status}`.

  Args:
  - bank: the started bank component.
  - sign-up-id: the sign-up's id.
  - request: `{:code}`."
  [bank sign-up-id request]
  (core/verify-code bank sign-up-id request))

(defn register-details
  "Register the person with the platform and carry the party onto the
  sign-up, answering `{:id :status :party-id :verification}`. A repeat
  reuses the idempotency key the first attempt minted.

  Args:
  - bank: the started bank component.
  - sign-up-id: the sign-up's id.
  - request: `{:given-name :family-name :date-of-birth :nationality
    :address :national-identifier}`, the date ISO 8601, the address
    `{:building-number :street :town :postcode :country}` and the
    identifier `{:type :value :issuing-country}`; nationality, country,
    type and issuing country default to a UK person's."
  [bank sign-up-id request]
  (core/register-details bank sign-up-id request))

(defn choose-passcode
  "Finish a sign-up: the customer, their passcode's hash and their
  first session, answering `{:token :expires-at :customer-id}`.

  Args:
  - bank: the started bank component.
  - sign-up-id: the sign-up's id.
  - request: `{:passcode}`."
  [bank sign-up-id request]
  (core/choose-passcode bank sign-up-id request))

(defn sign-in
  "A session for a returning customer, `{:token :expires-at}`, or an
  unauthorized anomaly that does not say which of the two was wrong.

  Args:
  - bank: the started bank component.
  - request: `{:phone :passcode}`."
  [bank request]
  (core/sign-in bank request))

(defn authenticate
  "The customer a session token resolves to — `{:id :party-id :phone
  :given-name :family-name :created-at :session-id}` — or an
  unauthorized anomaly when it resolves to none.

  Args:
  - bank: the started bank component.
  - token: the bearer the app sent, or nil."
  [bank token]
  (core/authenticate bank token))

(defn sign-out
  "End a session. Answers nil whether or not one was live.

  Args:
  - bank: the started bank component.
  - token: the bearer the app sent."
  [bank token]
  (core/sign-out bank token))

(defn record-account
  "Record an account the bank opened for the customer, answering the
  row: `{:customer-id :account-id :product-kind :name :opened-at}`.

  Args:
  - bank: the started bank component.
  - customer: as `authenticate` answers.
  - account: `{:account-id :product-kind :name}`, the platform's id,
    the app's short kind (`cur`, `sav` or `fix`) and the name the
    customer gave it."
  [bank customer account]
  (core/record-account bank customer account))

(defn customer-account
  "The account the customer holds under `account-id`, or an
  `:account/not-found` rejection — including for an account the bank
  opened for another customer.

  Args:
  - bank: the started bank component.
  - customer: as `authenticate` answers.
  - account-id: the platform's account id."
  [bank customer account-id]
  (core/customer-account bank customer account-id))

(defn me
  "The home read: `{:user :accounts :txns :payees :products}` in the
  shape the app's screens take, read from the platform for this
  customer's accounts and no other. Money is in minor units and times
  are RFC 3339.

  Args:
  - bank: the started bank component.
  - customer: as `authenticate` answers."
  [bank customer]
  (core/me bank customer))

(defn check-payee
  "Ask the payee's bank whether the name matches the account, answering
  `{:check-id :outcome :name-held}`: the outcome `match`,
  `close-match` with the name held, `no-match`, or `unavailable`.

  Args:
  - bank: the started bank component.
  - customer: as `authenticate` answers.
  - request: `{:name :sort-code :account-number}`, the sort code with
    or without dashes."
  [bank customer request]
  (core/check-payee bank customer request))

(defn submit-payment
  "Pay a payee from one of the customer's accounts, answering the
  payment: `{:id :status :from :payee :amount :currency :reference
  :created-at}`, the status `pending` until the scheme settles it.

  Args:
  - bank: the started bank component.
  - customer: as `authenticate` answers.
  - client-key: the key the app sent, or nil.
  - request: `{:from :payee :amount :reference}`, the payee either
    `{:id}` of one of the customer's or `{:name :sort-code
    :account-number}` of a new one, the amount in minor units."
  [bank customer client-key request]
  (core/submit-payment bank customer client-key request))

(defn transfer
  "Move money between two of the customer's accounts, settled as it is
  answered: `{:id :from :to :amount :currency :reference :created-at}`.

  Args:
  - bank: the started bank component.
  - customer: as `authenticate` answers.
  - client-key: the key the app sent, or nil.
  - request: `{:from :to :amount :reference}`, the amount in minor
    units."
  [bank customer client-key request]
  (core/transfer bank customer client-key request))

(defn open-account
  "Open an account against one of the bank's products, record it as
  the customer's, and move an opening deposit from their current
  account where one is given. Answers `{:account :deposit}`, the
  account as `me` lists it and the deposit as `transfer` answers, or
  nil where none was moved.

  Args:
  - bank: the started bank component.
  - customer: as `authenticate` answers.
  - client-key: the key the app sent, or nil.
  - request: `{:product-id :name :deposit}`, the name defaulting to
    the product's and the deposit, in minor units, to none."
  [bank customer client-key request]
  (core/open-account bank customer client-key request))
