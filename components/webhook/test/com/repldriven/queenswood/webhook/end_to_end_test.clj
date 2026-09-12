(ns ^:eftest/synchronized com.repldriven.queenswood.webhook.end-to-end-test
  "One account opening, from the changelog entries it writes to the
  signed POST a tenant's receiver answers (REQ-031, AC-07).

  The rig starts the consumer, the delivery runner and a receiver
  together, and nothing between the bus and that receiver is stubbed:
  the two envelopes an opening writes are published, the consumer
  writes the notification and its deliveries, and the runner claims and
  sends them. The opening leg and the opened leg both arrive, so the
  count this asserts is what holds the terminal-leg rule against the
  two entries rather than one.

  The envelopes are built from the Avro schema `cash-account`'s
  `changelog.clj` serialises with rather than by driving that brick:
  `brick:webhook :dev` runs in `external-adapters-service`, which hosts
  no `cash-account`.

  The legs in isolation are `events-test`'s, the runner's outcomes and
  bounds are `outbound-test`'s, and the signature against the published
  vector is `signing-test`'s."
  (:require
    [com.repldriven.queenswood.webhook.test-system]

    [com.repldriven.queenswood.webhook.store :as store]

    [com.repldriven.queenswood.cash-account-api.interface :as cash-account-api]
    [com.repldriven.queenswood.cash-account-query.interface :as
     cash-account-query]
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.event.interface :as event]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.server.interface :as server]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]])
  (:import
    (javax.crypto Mac)
    (javax.crypto.spec SecretKeySpec)
    (java.nio.charset StandardCharsets)
    (java.util Base64)))

(def ^:private config-file "classpath:webhook/end-to-end-test.yml")

(def ^:private event-name "cash-account-status-changed")

(def ^:private kind "cash-account.opened")

(def ^:private accounts-store "cash-accounts")

(def ^:private balances-store "balances")

(def ^:private sort-code "040404")

(def ^:private secret
  "The endpoint's signing secret. Base64 key material after the
  prefix, which is what a tenant's own verifier decodes."
  "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw")

(def ^:private id-header "webhook-id")

(def ^:private timestamp-header "webhook-timestamp")

(def ^:private signature-header "webhook-signature")

(def ^:private reachable
  "The runner's send-time address check, injected because every address
  this test can bind a receiver to is one the real rule refuses. What
  that rule refuses is `outbound-test`'s.

  A `reify` rather than a `fn`: starting a system rewraps every `fn` it
  finds in a component definition into a one-argument lifecycle
  function, and the runner calls this one with two arguments."
  (reify
   clojure.lang.IFn
     (invoke [_ _address _platform-hosts] nil)))

(defn- receiver
  "The tenant's endpoint. Each request is kept whole — headers and the
  body as it arrived — so what was signed is readable from the
  receiving side."
  [seen]
  (fn [_ctx]
    (fn [{:keys [uri headers request-method body]}]
      (swap! seen conj
        {:uri uri
         :headers headers
         :request-method request-method
         :body (some-> body
                       slurp)})
      {:status 200 :body "{}"})))

(defn- verify
  "A tenant's side of the check, written from the Standard Webhooks
  specification rather than from the code that produced the header:
  re-sign the id, the timestamp and the body under `secret` and look
  for that signature among the ones the header carries."
  [signing-secret message-id timestamp body header]
  (let [material (.decode (Base64/getDecoder)
                          (str/replace-first signing-secret #"^whsec_" ""))
        mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. material "HmacSHA256")))
        signed (.getBytes (str message-id "." timestamp "." body)
                          StandardCharsets/UTF_8)
        expected (str "v1,"
                      (.encodeToString (Base64/getEncoder)
                                       (.doFinal mac signed)))]
    (contains? (set (str/split header #" ")) expected)))

(defn- account
  "An opened current account as `cash-account.store` leaves it."
  [bank-id account-id]
  (let [now (utility/now)]
    {:bank-id bank-id
     :account-id account-id
     :account-type :account-type-business
     :party-id "pty.e2e"
     :product-id "prd.e2e"
     :version-id "prv.1"
     :product-type :product-type-sub-ledger-current
     :name account-id
     :currency "GBP"
     :account-status :cash-account-status-opened
     :payment-addresses [{:scheme :payment-address-scheme-scan
                          :scan {:sort-code sort-code
                                 :account-number "30000001"}}]
     :bban (str sort-code "30000001")
     :idempotency-key "ik-e2e"
     :created-at now
     :updated-at now}))

(defn- balance
  [bank-id account-id]
  (let [now (utility/now)]
    {:bank-id bank-id
     :account-id account-id
     :product-type :product-type-sub-ledger-current
     :balance-type :balance-type-default
     :balance-status :balance-status-posted
     :currency "GBP"
     :credit 0
     :debit 0
     :credit-carry 0
     :created-at now
     :updated-at now}))

(defn- seed-account
  [config acc bal]
  (fdb/transact config
                (fn [txn]
                  (fdb/save-record (fdb/open txn accounts-store)
                                   (schema/CashAccount->java acc))
                  (fdb/save-record (fdb/open txn balances-store)
                                   (schema/Balance->java bal))
                  nil)
                :test/seed
                "Failed to seed the account"))

(defn- endpoint
  "An endpoint pointing at `address`. Written through the store rather
  than registered: registration refuses every address a test can bind
  to, and what it refuses is `interface-test`'s."
  [bank-id endpoint-id address status]
  (let [now (utility/now)]
    {:bank-id bank-id
     :endpoint-id endpoint-id
     :address address
     :status status
     :secret secret
     :kinds [kind]
     :idempotency-key endpoint-id
     :created-at now
     :updated-at now}))

(defn- envelope
  "A relayed event as the changelog relay republishes one: the entry's
  own id as `:id`, and the payload under the schema
  `cash-account.changelog` serialises with."
  [sys {:keys [event-id bank-id account-id status-before status-after]}]
  (let [schemas (system/instance sys [:avro :serde])
        payload (avro/serialize (get schemas event-name)
                                {:bank-id bank-id
                                 :account-id account-id
                                 :status-before status-before
                                 :status-after status-after
                                 :change-kind :cash-account-change-kind-open})]
    (if (error/anomaly? payload)
      payload
      {:id event-id
       :event event-name
       :payload payload
       :causation-id account-id
       :correlation-id nil})))

(defn- eventually
  "Poll until `f` answers truthy, or give up. The consumer and the
  runner both work off their own threads, so neither has a completion
  the test can wait on."
  [f]
  (let [deadline (+ (utility/now) 30000)]
    (loop []
      (or (f)
          (when (< (utility/now) deadline)
            (Thread/sleep 50)
            (recur))))))

(deftest an-opening-reaches-the-tenants-receiver-signed-test
  (let [seen (atom [])]
    (with-test-system
     [sys
      [config-file
       #(-> %
            (assoc-in [:system/defs :receiver :handler] (receiver seen))
            (assoc-in [:system/defs :webhook :address-check] reachable))]]
     (let [config {:record-db (system/instance sys [:fdb :record-db])
                   :record-store (system/instance sys [:fdb :store])}
           bus (system/instance sys [:message-bus :bus])
           base-url (server/http-local-url
                     (system/instance sys [:receiver :jetty-adapter]))
           bank-id "bnk.e2e"
           account-id (utility/generate-id "acc")
           suffix (str (utility/uuidv7))
           enabled-id (str "whe.on." suffix)
           disabled-id (str "whe.off." suffix)
           seeded (account bank-id account-id)]
       (nom-test> [_ (seed-account config seeded (balance bank-id account-id))
                   _ (store/save-endpoint config
                                          (endpoint
                                           bank-id
                                           enabled-id
                                           (str base-url "/hooks")
                                           :webhook-endpoint-status-enabled))
                   _ (store/save-endpoint config
                                          (endpoint
                                           bank-id
                                           disabled-id
                                           (str base-url "/off")
                                           :webhook-endpoint-status-disabled))
                   opening (envelope sys
                                     {:event-id (str "evt.opening." suffix)
                                      :bank-id bank-id
                                      :account-id account-id
                                      :status-before nil
                                      :status-after
                                      :cash-account-status-opening})
                   opened (envelope sys
                                    {:event-id (str "evt.opened." suffix)
                                     :bank-id bank-id
                                     :account-id account-id
                                     :status-before :cash-account-status-opening
                                     :status-after :cash-account-status-opened})
                   _ (event/publish bus
                                    opening
                                    {:event-channel :cash-accounts-event})
                   _ (event/publish bus
                                    opened
                                    {:event-channel :cash-accounts-event})
                   _ (is (eventually #(seq @seen))
                         "the runner delivered to the receiver")
                   delivered
                   (eventually
                    (fn []
                      (let [rows (store/find-deliveries-by-endpoint config
                                                                    enabled-id)]
                        (when (and (not (error/anomaly? rows))
                                   (= [:webhook-delivery-status-delivered]
                                      (mapv :status rows)))
                          rows))))
                   _ (is (some? delivered)
                         "and the 200 was recorded against it")
                   written (store/find-notifications-by-bank config bank-id)
                   notification (first written)
                   record
                   (cash-account-query/find-account config bank-id account-id)
                   off (store/find-deliveries-by-endpoint config disabled-id)
                   request (first @seen)
                   body (:body request)
                   _ (testing
                       "the two entries an opening writes produced one of each"
                       (is (= 1 (count written)))
                       (is (= 1 (count delivered)))
                       (is (= 0 (count off))
                           "and none for the endpoint that is off"))
                   _ (testing "one POST reached the endpoint's own address"
                       (is (= 1 (count @seen)))
                       (is (= :post (:request-method request)))
                       (is (= "/hooks" (:uri request))))
                   _ (testing "carrying the Standard Webhooks headers"
                       (is (= (:delivery-id (first delivered))
                              (get-in request [:headers id-header])))
                       (is (some? (get-in request [:headers timestamp-header])))
                       (is (some? (get-in request
                                          [:headers signature-header]))))
                   _ (testing
                       "whose signature verifies under the endpoint's secret"
                       (is (verify secret
                                   (get-in request [:headers id-header])
                                   (get-in request [:headers timestamp-header])
                                   body
                                   (get-in request
                                           [:headers signature-header]))))
                   _ (testing
                       "and the bytes it signed are the notification's own"
                       (is (= (String. ^bytes (:body notification)
                                       StandardCharsets/UTF_8)
                              body)))
                   _ (testing
                       "whose data is the account as its read route renders it"
                       (let [sent (json/read-str body :key-fn keyword)
                             projected (cash-account-api/->wire-body record)]
                         (is (= (json/read-str (json/write-str projected)
                                               :key-fn
                                               keyword)
                                (:data sent)))
                         (is (= "opened" (:account-status (:data sent))))
                         (is (string? (:created-at (:data sent))))
                         (is (string? (:occurred-at sent)))
                         (is (= kind (:kind sent)))
                         (is (= account-id (:resource-id sent)))))])))))
