(ns com.repldriven.queenswood.webhook.events-test
  "The consumer against a real record store: which leg of a two-phase
  change produces a notification (AC-07), what a redelivered envelope
  produces (AC-08), what the stored body carries (AC-09) and what the
  envelope around it does (AC-10).

  Envelopes are built here from the same Avro schema the cash-account
  writer serialises with and handed to the processor directly, and once
  through the bus. Driving the `cash-account` brick instead would need
  a system this brick's own tests do not host.

  The endpoint lifecycle lives in `interface-test`, the record types in
  `store-test`, and the runner that sends what this writes in
  `outbound-test`."
  (:require
    [com.repldriven.queenswood.webhook.test-system]

    [com.repldriven.queenswood.webhook.events :as SUT]

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
    [com.repldriven.mono.processor.interface :as processor]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(def ^:private config-file "classpath:webhook/application-test.yml")

(def ^:private event-name "cash-account-status-changed")

(def ^:private kind "cash-account.opened")

(def ^:private accounts-store
  "Must match `cash-account.store`'s store name — the store the
  consumer's loader reads."
  "cash-accounts")

(def ^:private balances-store "balances")

(def ^:private sort-code "040404")

(defn- processor-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])
   :schemas (system/instance sys [:avro :serde])})

(defn- account
  "An opened current account as `cash-account.store` leaves it."
  [bank-id account-id account-number idempotency-key]
  (let [now (utility/now)]
    {:bank-id bank-id
     :account-id account-id
     :account-type :account-type-business
     :party-id "pty.events"
     :product-id "prd.events"
     :version-id "prv.1"
     :product-type :product-type-sub-ledger-current
     :name account-id
     :currency "GBP"
     :account-status :cash-account-status-opened
     :payment-addresses [{:scheme :payment-address-scheme-scan
                          :scan {:sort-code sort-code
                                 :account-number account-number}}]
     :bban (str sort-code account-number)
     :idempotency-key idempotency-key
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

(defn- endpoint
  [bank-id endpoint-id status kinds]
  (let [now (utility/now)]
    (utility/assoc-some
     {:bank-id bank-id
      :endpoint-id endpoint-id
      :address "https://tenant.example/hooks"
      :status status
      :secret "whsec_events"
      :idempotency-key endpoint-id
      :created-at now
      :updated-at now}
     :kinds
     (seq kinds))))

(defn- seed-account
  "Write the account and its balance the way the write bricks leave
  them. The loader reads the accounts store alone, so the balance is
  there to prove the projection carries what the read route's
  unenriched body carries and no more."
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

(defn- envelope
  "A relayed event as the changelog relay republishes one: the entry's
  own id as `:id`, and the payload Avro-encoded under the schema
  registered for the event name."
  [sys
   {:keys [event-id bank-id account-id status-before status-after
           change-kind]
    :or {change-kind :cash-account-change-kind-open}}]
  (let [schemas (system/instance sys [:avro :serde])
        payload (avro/serialize (get schemas event-name)
                                {:bank-id bank-id
                                 :account-id account-id
                                 :status-before status-before
                                 :status-after status-after
                                 :change-kind change-kind})]
    (if (error/anomaly? payload)
      payload
      {:id event-id
       :event event-name
       :payload payload
       :causation-id account-id
       :correlation-id nil})))

(defn- consume
  "Hand one envelope to the processor the rig started, which is what
  the bus subscription calls."
  [sys message]
  (processor/process (system/instance sys [:webhook :event-processor-impl])
                     message))

(defn- notifications
  [config bank-id]
  (store/find-notifications-by-bank config bank-id))

(defn- deliveries
  [config endpoint-id]
  (store/find-deliveries-by-endpoint config endpoint-id))

(defn- body->map
  [notification]
  (json/read-str (String. ^bytes (:body notification) "UTF-8")
                 :key-fn
                 keyword))

(defn- eventually
  "Poll until `f` answers truthy, or give up. The local bus delivers on
  its own thread, so a consume driven through it has no completion the
  test can wait on."
  [f]
  (let [deadline (+ (utility/now) 10000)]
    (loop []
      (or (f)
          (when (< (utility/now) deadline)
            (Thread/sleep 50)
            (recur))))))

(deftest opening-notifies-once-on-the-terminal-leg-test
  (with-test-system
   [sys config-file]
   (let [config (processor-config sys)
         bank-id "bnk.events.open"
         account-id (utility/generate-id "acc")
         suffix (str (utility/uuidv7))]
     (nom-test>
       [_ (seed-account config
                        (account bank-id account-id "20000001" "ik-open")
                        (balance bank-id account-id))
        _ (store/save-endpoint config
                               (endpoint bank-id
                                         (str "whe.a." suffix)
                                         :webhook-endpoint-status-enabled
                                         [kind]))
        _ (store/save-endpoint config
                               (endpoint bank-id
                                         (str "whe.b." suffix)
                                         :webhook-endpoint-status-enabled
                                         nil))
        opening (envelope sys
                          {:event-id (str "evt.opening." suffix)
                           :bank-id bank-id
                           :account-id account-id
                           :status-before nil
                           :status-after :cash-account-status-opening})
        opened (envelope sys
                         {:event-id (str "evt.opened." suffix)
                          :bank-id bank-id
                          :account-id account-id
                          :status-before :cash-account-status-opening
                          :status-after :cash-account-status-opened})
        _ (testing "the leg landing on opening writes nothing"
            (let [result (consume sys opening)]
              (is (not (error/anomaly? result)))
              (nom-test> [written (notifications config bank-id)
                          _ (is (= 0 (count written)))])))
        _ (testing "the terminal leg writes one notification"
            (let [result (consume sys opened)]
              (is (not (error/anomaly? result)))))
        written (notifications config bank-id)
        _ (is (= 1 (count written)))
        _
        (testing "and one delivery per enabled endpoint that chose it"
          (nom-test> [chosen (deliveries config (str "whe.a." suffix))
                      all-kinds (deliveries config (str "whe.b." suffix))
                      _ (is (= 1 (count chosen)))
                      _ (is (= 1 (count all-kinds)))
                      _ (is (= #{:webhook-delivery-status-pending}
                               (set (map :status (concat chosen all-kinds)))))
                      _ (is (= #{kind}
                               (set (map :kind (concat chosen all-kinds)))))]))]))))

(deftest a-redelivered-envelope-is-a-no-op-test
  (with-test-system
   [sys config-file]
   (let [config (processor-config sys)
         bank-id "bnk.events.redeliver"
         account-id (utility/generate-id "acc")
         suffix (str (utility/uuidv7))
         endpoint-id (str "whe." suffix)]
     (nom-test> [_ (seed-account
                    config
                    (account bank-id account-id "20000002" "ik-redeliver")
                    (balance bank-id account-id))
                 _ (store/save-endpoint config
                                        (endpoint
                                         bank-id
                                         endpoint-id
                                         :webhook-endpoint-status-enabled
                                         [kind]))
                 opened (envelope sys
                                  {:event-id (str "evt." suffix)
                                   :bank-id bank-id
                                   :account-id account-id
                                   :status-before :cash-account-status-opening
                                   :status-after :cash-account-status-opened})
                 _ (testing "both consumes acknowledge"
                     (is (not (error/anomaly? (consume sys opened))))
                     (is (not (error/anomaly? (consume sys opened)))))
                 written (notifications config bank-id)
                 sent (deliveries config endpoint-id)
                 _ (testing "and the unique index left one of each"
                     (is (= 1 (count written)))
                     (is (= 1 (count sent))))]))))

(deftest the-stored-body-is-the-read-route-projection-test
  (with-test-system
   [sys config-file]
   (let [config (processor-config sys)
         bank-id "bnk.events.body"
         account-id (utility/generate-id "acc")
         suffix (str (utility/uuidv7))
         seeded (account bank-id account-id "20000003" "ik-body")]
     (nom-test>
       [_ (seed-account config seeded (balance bank-id account-id))
        opened (envelope sys
                         {:event-id (str "evt." suffix)
                          :bank-id bank-id
                          :account-id account-id
                          :status-before :cash-account-status-opening
                          :status-after :cash-account-status-opened})
        _ (consume sys opened)
        written (notifications config bank-id)
        notification (first written)
        record (cash-account-query/find-account config bank-id account-id)
        body (body->map notification)
        _ (testing "data is the resource's own projection, field for field"
            (let [projected (cash-account-api/->wire-body record)
                  round-tripped
                  (json/read-str (json/write-str projected) :key-fn keyword)]
              (is (= round-tripped (:data body)))))
        _ (testing "and carries no key the projection drops"
            (is (some? (:idempotency-key record))
                "the record holds one, so dropping it is a choice")
            (is (nil? (:idempotency-key (cash-account-api/->wire-body record))))
            (is (nil? (:idempotency-key (:data body)))))
        _ (testing "while the keys it keeps are all there"
            (is (= (:bban seeded) (:bban (:data body))))
            (is (= account-id (:account-id (:data body)))))
        _
        (testing
          "and is spelled as the read route spells it — the enums as
                   the strings `CashAccount` admits and the timestamps
                   as ISO-8601, which is what AC-09 asks for and what a
                   client generated from the document accepts"
          (is (= "opened" (:account-status (:data body))))
          (is (= "business" (:account-type (:data body))))
          (is (= "current" (:product-type (:data body))))
          (is (string? (:created-at (:data body))))
          (is (some? (re-matches #"\d{4}-\d{2}-\d{2}T.*"
                                 (:created-at (:data body))))))
        _ (testing "and the envelope's own timestamp with it"
            (is (string? (:occurred-at body)))
            (is (some? (re-matches #"\d{4}-\d{2}-\d{2}T.*"
                                   (:occurred-at body)))))
        _ (testing "while the embedded collections are not carried"
            (is (nil? (:balances (:data body))))
            (is (nil? (:transactions (:data body)))))]))))

(deftest the-envelope-carries-every-published-field-test
  (with-test-system
   [sys config-file]
   (let [config (processor-config sys)
         bank-id "bnk.events.envelope"
         account-id (utility/generate-id "acc")
         suffix (str (utility/uuidv7))
         event-id (str "evt." suffix)]
     (nom-test> [_ (seed-account
                    config
                    (account bank-id account-id "20000004" "ik-envelope")
                    (balance bank-id account-id))
                 opened (envelope sys
                                  {:event-id event-id
                                   :bank-id bank-id
                                   :account-id account-id
                                   :status-before :cash-account-status-opening
                                   :status-after :cash-account-status-opened})
                 _ (consume sys opened)
                 written (notifications config bank-id)
                 notification (first written)
                 body (body->map notification)
                 _ (testing "every field REQ-017 names is on the envelope"
                     (is (= #{:notification-id :kind :change-kind :occurred-at
                              :bank-id :resource-type :resource-id
                              :status-before :status-after :idempotency-key
                              :correlation-id :data}
                            (set (keys body)))))
                 _ (testing
                     "including the key the open-cash-account request carried"
                     (is (= "ik-envelope" (:idempotency-key body)))
                     (is (= "ik-envelope" (:idempotency-key notification))))
                 _ (testing
                     "the statuses are published as the read route spells them"
                     (is (= "opening" (:status-before body)))
                     (is (= "opened" (:status-after body))))
                 _ (testing "the change kind travels as its own short field"
                     (is (= "open" (:change-kind body)))
                     (is (= kind (:kind body))))
                 _ (testing
                     "and the row records which relayed entry produced it"
                     (is (= event-id (:changelog-event-id notification)))
                     (is (= event-id (:correlation-id body))))]))))

(deftest an-endpoint-that-did-not-choose-the-kind-is-not-told-test
  (with-test-system
   [sys config-file]
   (let [config (processor-config sys)
         bank-id "bnk.events.filter"
         account-id (utility/generate-id "acc")
         suffix (str (utility/uuidv7))
         disabled-id (str "whe.disabled." suffix)
         other-kind-id (str "whe.other." suffix)]
     (nom-test> [_ (seed-account
                    config
                    (account bank-id account-id "20000005" "ik-filter")
                    (balance bank-id account-id))
                 _ (store/save-endpoint config
                                        (endpoint
                                         bank-id
                                         disabled-id
                                         :webhook-endpoint-status-disabled
                                         [kind]))
                 _ (store/save-endpoint config
                                        (endpoint
                                         bank-id
                                         other-kind-id
                                         :webhook-endpoint-status-enabled
                                         ["cash-account.closed"]))
                 opened (envelope sys
                                  {:event-id (str "evt." suffix)
                                   :bank-id bank-id
                                   :account-id account-id
                                   :status-before :cash-account-status-opening
                                   :status-after :cash-account-status-opened})
                 _ (consume sys opened)
                 written (notifications config bank-id)
                 _ (testing
                     "the notification is written even with nobody to tell"
                     (is (= 1 (count written))))
                 _ (testing "but neither endpoint earns a delivery"
                     (nom-test> [off (deliveries config disabled-id)
                                 other (deliveries config other-kind-id)
                                 _ (is (= 0 (count off)))
                                 _ (is (= 0 (count other)))]))]))))

(deftest an-uncatalogued-change-is-acknowledged-unwritten-test
  (with-test-system
   [sys config-file]
   (let [config (processor-config sys)
         bank-id "bnk.events.uncatalogued"
         account-id (utility/generate-id "acc")
         suffix (str (utility/uuidv7))]
     (nom-test> [_ (seed-account
                    config
                    (account bank-id account-id "20000006" "ik-unknown")
                    (balance bank-id account-id))
                 rotated (envelope sys
                                   {:event-id (str "evt.rotate." suffix)
                                    :bank-id bank-id
                                    :account-id account-id
                                    :status-before :cash-account-status-opened
                                    :status-after :cash-account-status-opened
                                    :change-kind
                                    :cash-account-change-kind-rotate-address})
                 _ (testing "a change kind with no entry acknowledges"
                     (is (not (error/anomaly? (consume sys rotated)))))
                 _ (testing
                     "an event with no entry acknowledges without decoding"
                     (is (not (error/anomaly? (consume
                                               sys
                                               {:id (str "evt.party." suffix)
                                                :event "party-status-changed"
                                                :payload (byte-array 0)})))))
                 written (notifications config bank-id)
                 _ (is (= 0 (count written)))]))))

(deftest the-bus-subscription-reaches-the-consumer-test
  (with-test-system
   [sys config-file]
   (let [config (processor-config sys)
         bus (system/instance sys [:message-bus :bus])
         bank-id "bnk.events.bus"
         account-id (utility/generate-id "acc")
         suffix (str (utility/uuidv7))
         endpoint-id (str "whe." suffix)]
     (nom-test> [_ (seed-account
                    config
                    (account bank-id account-id "20000007" "ik-bus")
                    (balance bank-id account-id))
                 _ (store/save-endpoint config
                                        (endpoint
                                         bank-id
                                         endpoint-id
                                         :webhook-endpoint-status-enabled
                                         [kind]))
                 opened (envelope sys
                                  {:event-id (str "evt." suffix)
                                   :bank-id bank-id
                                   :account-id account-id
                                   :status-before :cash-account-status-opening
                                   :status-after :cash-account-status-opened})
                 _ (testing
                     "the registered kind starts this brick's own processor"
                     (is (= (type (SUT/->WebhookEventProcessor config))
                            (type (system/instance sys
                                                   [:webhook
                                                    :event-processor-impl])))))
                 _ (event/publish bus
                                  opened
                                  {:event-channel :cash-accounts-event})
                 _ (testing "and the subscription carries an envelope to it"
                     (is (eventually (fn []
                                       (let [written (notifications config
                                                                    bank-id)]
                                         (and (not (error/anomaly? written))
                                              (= 1 (count written))))))))
                 _ (testing "and the delivery it wrote is there to be claimed"
                     (nom-test> [sent (deliveries config endpoint-id)
                                 _ (is (= 1 (count sent)))]))]))))
