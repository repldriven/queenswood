(ns com.repldriven.queenswood.api-schema.components
  (:require
    [com.repldriven.queenswood.api-schema.coercion :refer [enum-coercion]]
    [com.repldriven.queenswood.api-schema.schema :refer
     [components-registry id-schema]]

    [malli.core :as m]
    [malli.json-schema :as mjs])
  (:import
    (java.time Instant LocalDate ZoneOffset)
    (java.time.format DateTimeParseException)))

;; Custom malli schema type: :unique-vector behaves like :vector but
;; rejects arrays that contain duplicate items. Same OpenAPI shape as
;; :set (`{type:array, items:..., minItems:..., uniqueItems:true}`),
;; without :set's silent dedup at decode time. Emitted via
;; `malli.json-schema/accept` so consumers see uniqueItems:true.

;; Two flavours of the custom schema, both emitting the same
;; `{type:array, items:..., uniqueItems:true}` in OpenAPI:
;;
;;   :unique-vector      — strict. Rejects duplicate items at
;;                         validation. Use on *request* bodies so
;;                         clients get a 400 for non-unique arrays.
;;
;;   :unique-vector-lax  — shape-only. Accepts duplicates at
;;                         runtime. Use on *response* bodies where
;;                         stored records may contain duplicates
;;                         (e.g. from historical writes); we still
;;                         advertise `uniqueItems:true` to clients.
;;
;; Why the split: the strict pred runs on raw pre-decode data, so
;; two different invalid enum strings can sneak through (both
;; decoding to the `:x-unknown` sentinel) and end up as duplicates
;; in storage. Rather than chase every decoder edge-case, we just
;; tolerate duplicates on the read path.

(def unique-vector-schema
  (m/-collection-schema
   {:type :unique-vector
    ;; `sequential?` not `vector?` — JSON-decoded request bodies
    ;; arrive as vectors, but protojure returns `repeated` fields as
    ;; lazy seqs on the response path.
    :pred (fn [v] (and (sequential? v) (or (empty? v) (apply distinct? v))))
    :empty []}))

(def unique-vector-lax-schema
  (m/-collection-schema {:type :unique-vector-lax :pred sequential? :empty []}))

(defn- -unique-vector-apidocs
  [schema children]
  (let [{:keys [min max]} (m/properties schema)]
    (cond-> {:type "array"
             :items (first children)
             :uniqueItems true}
            min
            (assoc :minItems min)
            max
            (assoc :maxItems max))))

(defmethod mjs/accept :unique-vector
  [_ schema children _]
  (-unique-vector-apidocs schema children))

(defmethod mjs/accept :unique-vector-lax
  [_ schema children _]
  (-unique-vector-apidocs schema children))

(defn- iso-date->epoch-day
  "Parses a strict ISO-8601 calendar date into an `int64` epoch-day.
  Returns nil when `s` isn't a real calendar date."
  [s]
  (when (string? s)
    (try (.toEpochDay (LocalDate/parse ^String s))
         (catch DateTimeParseException _ nil))))

(defn- epoch-day->iso-date
  [n]
  (when (int? n) (str (LocalDate/ofEpochDay (long n)))))

(defn- iso-date->yyyymmdd
  "Packs a strict ISO-8601 calendar date (validated by
  `java.time.LocalDate/parse`) into a YYYYMMDD int. Returns nil when
  `s` isn't a real calendar date — so month/day combinations the
  shape regex lets through (Nov 31, Feb 30, non-leap Feb 29) are
  rejected here too."
  [s]
  (when (string? s)
    (try
      (let [d (LocalDate/parse ^String s)]
        (+ (* (.getYear d) 10000)
           (* (.getMonthValue d) 100)
           (.getDayOfMonth d)))
      (catch DateTimeParseException _ nil))))

(defn- valid-iso-date?
  "True when `s` parses as a real ISO-8601 calendar date — catches
  month/day combinations the regex alone can't (Nov 31, Feb 30,
  non-leap Feb 29)."
  [s]
  (and (string? s)
       (try (some? (LocalDate/parse ^String s))
            (catch DateTimeParseException _ false))))

(defn- valid-yyyymmdd?
  "True when `n` decomposes into a real calendar date — year
  `(quot n 10000)`, month `(mod (quot n 100) 100)`, day `(mod n 100)`.
  Rejects nonsense ints like 0 or 99999999 that the int-type alone
  lets through."
  [n]
  (and (int? n)
       (try (some? (LocalDate/of (int (quot n 10000))
                                 (int (mod (quot n 100) 100))
                                 (int (mod n 100))))
            (catch Exception _ false))))

(defn- past-yyyymmdd?
  "True when the packed-YYYYMMDD int decomposes into a real calendar
  date that is strictly before today (UTC)."
  [n]
  (and (valid-yyyymmdd? n)
       (.isBefore (LocalDate/of (int (quot n 10000))
                                (int (mod (quot n 100) 100))
                                (int (mod n 100)))
                  (LocalDate/now ZoneOffset/UTC))))

(defn- parse-timestamp
  [s]
  (when (string? s)
    (try (.toEpochMilli (Instant/parse s))
         (catch DateTimeParseException _
           (try (.toEpochMilli
                 (.toInstant (.atStartOfDay (LocalDate/parse s)
                                            ZoneOffset/UTC)))
                (catch DateTimeParseException _ s))))))

(defn- yyyymmdd->iso-date
  [n]
  (format "%04d-%02d-%02d"
          (quot n 10000)
          (mod (quot n 100) 100)
          (mod n 100)))

(def AccountNumber
  [:re
   {:title "AccountNumber" :json-schema/example "12345678"}
   #"^[0-9]{8}$"])

(def Amount
  "Monetary amount paired with its currency — mirrors the `Amount`
  proto in `schemas/amounts/amount.proto` (int64 minor units + ISO
  4217 currency)."
  [:map {:closed true}
   [:value [:ref "MinorUnits"]]
   [:currency [:ref "Currency"]]])

(def Bban
  [:re
   {:title "Bban" :json-schema/example "04000412345678"}
   #"^[0-9]{14}$"])

(def CountryCode
  "ISO 3166-1 alpha-2 country code: two uppercase ASCII letters.
  Matches the `nationality` field contract declared in
  `person-identification.proto`."
  [:re
   {:title "CountryCode" :json-schema/example "GB"}
   #"^[A-Z]{2}$"])

(def Country3Code
  "ISO 3166-1 alpha-3 country code: three uppercase ASCII letters.
  Used by address fields — the Entrust/Onfido applicant accepts
  alpha-3 in `address.country`, separate from our alpha-2
  `nationality`."
  [:re
   {:title "Country3Code" :json-schema/example "GBR"}
   #"^[A-Z]{3}$"])

(def Currency
  "Closed enum of currencies the system natively supports. Stricter
  than `CurrencyCode` — request bodies use this to reject unsupported
  ISO codes at coercion time.

  A symbol added here has to be added to `allowed-currencies` in
  `components/resources/resources/cash-account-product-templates/own-funds.yml`
  too: a bank create opens an own-funds house account for every
  currency it names, and a template that omits one rejects the
  account, rolling the whole create back."
  [:enum {:json-schema/example "EUR"} "EUR" "GBP" "USD"])

(def CurrencyCode
  "ISO 4217 currency code: exactly three uppercase ASCII letters.

  Looser than the `Currency` enum (EUR/GBP/USD) — use for response
  fields that may carry historical or out-of-catalog currencies we
  must not reject at response-coercion time."
  [:re
   {:title "CurrencyCode" :json-schema/example "GBP"}
   #"^[A-Z]{3}$"])

(def Date
  "ISO 8601 calendar date (YYYY-MM-DD). Kept as a string end-to-end
  — distinct from Timestamp, which carries epoch-millis and encodes
  to a date-time string only at the API boundary.

  Combines a regex gate (correct shape, month 01-12, day 01-31) with
  a `java.time.LocalDate/parse` predicate so month-specific day
  counts (Nov 31, Feb 30, non-leap Feb 29) are rejected at coercion
  as well."
  [:and
   [:re
    {:title "Date" :json-schema/format "date" :json-schema/example "2025-01-01"}
    #"^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$"]
   [:fn {:error/message "must be a valid calendar date"} valid-iso-date?]])

(def BusinessDay
  "ISO 8601 calendar date at the API boundary, stored as an `int64`
  epoch-day internally (matches the `business_day` field on the
  payment protos and Avro schemas). Decoded/encoded via `:api` so
  the storage format never leaks to clients."
  [:and
   {:json-schema {:type "string"
                  :format "date"
                  :pattern "^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$"
                  :example "2025-01-01"}}
   [:int
    {:decode/api (fn [v]
                   (cond (int? v)
                         v
                         (string? v)
                         (or (iso-date->epoch-day v) v)
                         :else
                         v))
     :encode/api epoch-day->iso-date}]])

(def DateOfBirth
  "ISO 8601 calendar date at the API boundary, stored as a packed
  `YYYYMMDD` integer internally (matches the `int32` field on the
  `PersonIdentification` proto). Decoded/encoded via `:api` so the
  storage format never leaks to clients. The `:fn valid-yyyymmdd?`
  predicate rejects raw-int payloads (e.g. `0`) that happen to
  satisfy `[:int]` but don't decompose into a real calendar date."
  [:and
   {:json-schema {:type "string"
                  :format "date"
                  :pattern "^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$"
                  :example "1950-07-27"}}
   [:int
    {:decode/api (fn [v]
                   (cond (int? v)
                         v
                         (string? v)
                         (or (iso-date->yyyymmdd v) v)
                         :else
                         v))
     :encode/api (fn [n] (when (int? n) (yyyymmdd->iso-date n)))}]
   [:fn {:error/message "must be a valid calendar date in the past"}
    past-yyyymmdd?]])

(def EmbedQuery
  "Nested `embed` deepObject query parameter. Wire form is
  `embed[balances]=true&embed[transactions]=false`, nested into
  `{:balances …, :transactions …}` by the `nest-bracket-query-params`
  interceptor before malli validation runs."
  [:map {:closed true}
   [:balances {:optional true} boolean?]
   [:transactions {:optional true} boolean?]])

(def IdempotencyKey
  "Client-generated idempotency key. 16-255 chars of URL-safe ASCII
  (letters, digits, `_`, `-`) — UUID v4 or ULID both fit."
  [:and
   [:string
    {:min 16
     :max 255
     :description "Client-generated idempotency key. UUID v4 recommended."
     :json-schema/format "idempotency-key"}]
   [:re #"^[A-Za-z0-9_\-]+$"]])

(def MinorUnits
  [:int
   {:min 0
    :max 10000000000000
    :json-schema/format "int64"
    :description
    "Monetary quantity in the smallest denomination of the associated currency."}])

(def PaymentMinorUnits
  [:int
   {:min 1
    :max 10000000000000
    :json-schema/format "int64"
    :description
    "Positive monetary quantity in the smallest denomination of the
    associated currency. Used for payment submission requests to
    reject zero amounts at the API boundary."}])

(def PageQuery
  "Nested `page` deepObject query parameter. Wire form is
  `page[after]=…&page[size]=20`, nested into
  `{:after …, :before …, :size …}` by the `nest-bracket-query-params`
  interceptor before malli validation runs.

  `size` is declared as a bounded integer so clients (and fuzzers)
  can't slip through non-numeric or out-of-range values — the
  string-transformer coerces the incoming query-string digits to an
  int, and validation rejects anything outside `[1, 100]`. Cursor
  fields are opaque strings with a min length so blanks are rejected
  at validation rather than being silently treated as \"no cursor\"."
  [:map {:closed true}
   [:after {:optional true} [:string {:min 1 :max 200}]]
   [:before {:optional true} [:string {:min 1 :max 200}]]
   [:size {:optional true} [:int {:min 1 :max 100}]]])

(def Name
  "Non-empty printable text up to 140 chars. Used wherever the
  API exposes a human-readable name (party display names, payment
  creditor names, organization / product / cash-account / api-key
  names) — same shape as the CoP spec's CreditorAccountName.

  The regex excludes ASCII C0/C1 control characters via explicit
  byte ranges (`\\x00-\\x1F\\x7F-\\x9F`) rather than `\\p{Cc}` so
  Python-based consumers (schemathesis / jsonschema) can compile
  it."
  [:re
   {:title "Name" :json-schema/example "Arthur Dent"}
   #"^(?!\s*$)[^\x00-\x1F\x7F-\x9F]{1,140}$"])

(def NationalIdentifierValue
  "Opaque national-identifier value (e.g. UK NI number, passport
  number, tax id). Format differs per `IdentifierType` / issuing
  country, so we don't regex-validate the shape — just enforce
  non-empty and an upper bound to block empty-string collisions on
  the `(organization, type, value)` uniqueness index."
  [:string
   {:title "NationalIdentifierValue"
    :min 1
    :max 64
    :json-schema/example "ZZ999999D"}])

(def SignedAmount
  "Signed monetary amount paired with its currency — same shape as
  `Amount` but permits negative values (e.g. available balances that
  may dip below zero). The value is unbounded because it represents
  an accumulated running total, not a single transaction amount."
  [:map {:closed true}
   [:value [:int {:json-schema/format "int64"}]]
   [:currency [:ref "Currency"]]])

(def SignedBasisPoints
  [:int
   {:min -1000000
    :max 1000000
    :json-schema/format "int32"
    :description "Signed rate in basis points. Negative rates permitted."}])

(def SignedMinorUnits
  [:int
   {:min -10000000000000
    :max 10000000000000
    :json-schema/format "int64"
    :description
    "Signed monetary quantity in the smallest denomination of the associated currency."}])

(def SortCode
  "A bank's 6-digit sort code (the first six digits of its accounts'
  BBANs), allocated per bank from the sort-code fountain."
  [:re
   {:title "SortCode" :json-schema/example "000001"}
   #"^[0-9]{6}$"])

(def Timestamp
  [:int
   {:decode/api (fn [v]
                  (cond (int? v)
                        v
                        (string? v)
                        (or (parse-timestamp v) v)
                        :else
                        v))
    :encode/api (fn [ms] (when (pos? ms) (str (Instant/ofEpochMilli ms))))
    :json-schema {:type "string" :format "date-time"}}])

(def id-examples
  {"BankId" "bnk.01kprbmgcj35ptc8npmybhh4s7"
   "PartyId" "pty.01kprbmgcj35ptc8npmybhh4s9"
   "ProductId" "prd.01kprbmgcj35ptc8npmybhh4se"
   "VersionId" "prv.01kprbmgcj35ptc8npmybhh4sf"})

(def BankId (id-schema "BankId" "bnk" (id-examples "BankId")))

(def PartyId (id-schema "PartyId" "pty" (id-examples "PartyId")))

(def ProductId (id-schema "ProductId" "prd" (id-examples "ProductId")))

(def VersionId (id-schema "VersionId" "prv" (id-examples "VersionId")))

(def product-type-enum
  (enum-coercion {"current" :product-type-sub-ledger-current
                  "savings" :product-type-sub-ledger-savings
                  "term-deposit" :product-type-sub-ledger-term-deposit
                  "own-funds" :product-type-sub-ledger-own-funds}
                 :product-type-unknown))

(def payment-address-scheme-enum
  (enum-coercion {"scan" :payment-address-scheme-scan
                  "iban" :payment-address-scheme-iban
                  "swift" :payment-address-scheme-swift
                  "ach" :payment-address-scheme-ach}
                 :payment-address-scheme-unknown))

(def ProductType
  ((:enum-schema product-type-enum) {:json-schema/example "current"}))

(def PaymentAddressScheme
  ((:enum-schema payment-address-scheme-enum) {:json-schema/example "scan"}))

(def registry
  (components-registry
   [#'AccountNumber #'Amount #'Bban #'BankId #'BusinessDay #'CountryCode
    #'Country3Code #'Currency #'CurrencyCode #'Date #'DateOfBirth #'EmbedQuery
    #'IdempotencyKey #'MinorUnits #'Name #'PartyId #'PaymentAddressScheme
    #'PaymentMinorUnits #'NationalIdentifierValue #'PageQuery #'ProductId
    #'ProductType #'SignedAmount #'SignedBasisPoints #'SignedMinorUnits
    #'SortCode #'Timestamp #'VersionId]))
