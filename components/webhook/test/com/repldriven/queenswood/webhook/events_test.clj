(ns com.repldriven.queenswood.webhook.events-test
  "The consumer against a real record store: which leg of a two-phase
  change produces a notification (AC-07), what a redelivered envelope
  produces (AC-08) and what the envelope carries (AC-10). That a
  notification's data is the resource as its read route returns it
  (AC-09) is the API journeys', which compare the two over HTTP.

  Envelopes are built here from the same Avro schema the cash-account
  writer serialises with and handed to the processor directly. The
  bus subscription and the delivery the runner makes of what this
  writes are the API journeys', under `journeys/webhooks/`.

  The endpoint lifecycle lives in `interface-test`, the record types in
  `store-test`, and the runner that sends what this writes in
  `outbound-test`."
  (:require
    [com.repldriven.queenswood.webhook.test-system]

    [com.repldriven.queenswood.webhook.store :as store]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.party-api.interface :as party-api]
    [com.repldriven.queenswood.party-query.interface :as party-query]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error]
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

(def ^:private balances-store "account-balances")

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
     :version-from-on 20089
     :created-by {:kind :actor-kind-operator :principal-id "test"}
     :product-type :product-type-sub-ledger-current
     :name account-id
     :currency "GBP"
     :status :cash-account-status-opened
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
                                   (schema/AccountBalance->java bal))
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
  ;; this brick's own event handler, handed an envelope, no bus
  ;; nosemgrep: brick-test-drives-pipeline
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
                 closing (envelope sys
                                   {:event-id (str "evt.closing." suffix)
                                    :bank-id bank-id
                                    :account-id account-id
                                    :status-before :cash-account-status-opened
                                    :status-after :cash-account-status-closing
                                    :change-kind
                                    :cash-account-change-kind-close})
                 _ (testing "a leg with no entry acknowledges"
                     (is (not (error/anomaly? (consume sys closing)))))
                 _ (testing
                     "an event with no entry acknowledges without decoding"
                     (is (not (error/anomaly? (consume
                                               sys
                                               {:id (str "evt.idv." suffix)
                                                :event "idv-status-changed"
                                                :payload (byte-array 0)})))))
                 written (notifications config bank-id)
                 _ (is (= 0 (count written)))]))))

(deftest each-account-transition-is-its-own-kind-test
  (with-test-system
   [sys config-file]
   (let [config (processor-config sys)
         bank-id "bnk.events.transitions"
         account-id (utility/generate-id "acc")
         suffix (str (utility/uuidv7))
         leg (fn [n change-kind before after]
               (envelope sys
                         {:event-id (str "evt." n "." suffix)
                          :bank-id bank-id
                          :account-id account-id
                          :status-before before
                          :status-after after
                          :change-kind change-kind}))]
     (nom-test>
       [_ (seed-account config
                        (account bank-id account-id "20000008" "ik-legs")
                        (balance bank-id account-id))
        legs [(leg "suspend" :cash-account-change-kind-suspend
                   :cash-account-status-opened :cash-account-status-suspended)
              (leg "resume" :cash-account-change-kind-resume
                   :cash-account-status-suspended :cash-account-status-opened)
              (leg "rotate" :cash-account-change-kind-rotate-address
                   :cash-account-status-opened :cash-account-status-opened)
              (leg "migrate" :cash-account-change-kind-migrate
                   :cash-account-status-suspended
                   :cash-account-status-suspended)
              (leg "closed" :cash-account-change-kind-close
                   :cash-account-status-closing :cash-account-status-closed)]
        _ (is (not-any? error/anomaly? (map (partial consume sys) legs)))
        written (notifications config bank-id)
        bodies (map body->map written)
        _ (testing "each transition is told under a kind of its own"
            (is (= #{"cash-account.suspended" "cash-account.resumed"
                     "cash-account.address-rotated" "cash-account.migrated"
                     "cash-account.closed"}
                   (set (map :kind bodies))))
            (is (= 5 (count bodies))))
        _
        (testing
          "a transition that leaves the status alone is told on
                   whatever status the account holds"
          (is (= #{["rotate-address" "opened"] ["migrate" "suspended"]}
                 (into #{}
                       (comp (filter (comp #{"rotate-address" "migrate"}
                                           :change-kind))
                             (map (juxt :change-kind :status-after)))
                       bodies))))]))))

(def ^:private party-event-name "party-status-changed")

(def ^:private parties-store "Must match `party.store`'s store name." "parties")

(defn- party
  "A person as `party.store` leaves it."
  [bank-id party-id status]
  (let [now (utility/now)]
    {:bank-id bank-id
     :party-id party-id
     :party-type :party-type-person
     :legal-name "Arthur Dent"
     :display-name "Arthur Dent"
     :status status
     :idempotency-key (str "ik-" party-id)
     :created-at now
     :created-by {:kind :actor-kind-operator :principal-id "queenswood-admin"}
     :updated-at now}))

(defn- seed-party
  [config party]
  (fdb/transact config
                (fn [txn]
                  (fdb/save-record (fdb/open txn parties-store)
                                   (schema/Party->java party))
                  nil)
                :test/seed
                "Failed to seed the party"))

(defn- party-envelope
  [sys {:keys [event-id bank-id party-id status-before status-after]}]
  (let [schemas (system/instance sys [:avro :serde])
        payload (avro/serialize (get schemas party-event-name)
                                {:bank-id bank-id
                                 :party-id party-id
                                 :status-before status-before
                                 :status-after status-after})]
    (if (error/anomaly? payload)
      payload
      {:id event-id
       :event party-event-name
       :payload payload
       :causation-id party-id
       :correlation-id nil})))

(deftest a-party-is-told-by-where-its-transition-lands-test
  (with-test-system
   [sys config-file]
   (let [config (processor-config sys)
         bank-id "bnk.events.party"
         party-id (utility/generate-id "pty")
         suffix (str (utility/uuidv7))
         leg (fn [n before after]
               (party-envelope sys
                               {:event-id (str "evt.party." n "." suffix)
                                :bank-id bank-id
                                :party-id party-id
                                :status-before before
                                :status-after after}))]
     (nom-test>
       [_ (seed-party config (party bank-id party-id :party-status-active))
        _ (store/save-endpoint config
                               (endpoint bank-id
                                         (str "whe.p." suffix)
                                         :webhook-endpoint-status-enabled
                                         ["party.opened"]))
        created (leg "create" nil :party-status-pending)
        _ (is (not (error/anomaly? (consume sys created))))
        none (notifications config bank-id)
        _ (testing "a creation is acknowledged and writes nothing"
            (is (empty? none)))
        opened (leg "open" :party-status-pending :party-status-active)
        resumed (leg "resume" :party-status-suspended :party-status-active)
        _ (is (not (error/anomaly? (consume sys opened))))
        _ (is (not (error/anomaly? (consume sys resumed))))
        written (notifications config bank-id)
        bodies (map body->map written)
        _
        (testing
          "two transitions landing on active are told apart by the
                   status each left"
          (is (= #{["party.opened" "open" "pending"]
                   ["party.resumed" "resume" "suspended"]}
                 (into #{}
                       (map (juxt :kind :change-kind :status-before))
                       bodies))))
        record (party-query/get-party config bank-id party-id)
        _ (testing "data is the party's own projection, field for field"
            (let [projected (party-api/->wire-body record)]
              (is (= (json/read-str (json/write-str projected) :key-fn keyword)
                     (:data (first bodies))))
              (is (= "Party" (:resource-type (first bodies))))
              (is (nil? (:idempotency-key (:data (first bodies)))))))
        _ (testing "and only the endpoint's chosen kind is delivered"
            (nom-test> [chosen (deliveries config (str "whe.p." suffix))
                        _ (is (= 1 (count chosen)))]))]))))

(def ^:private outbound-event-name "outbound-payment-status-changed")

(def ^:private outbound-kind "payment.outbound-completed")

(def ^:private outbound-store
  "Must match `payment.store`'s store name — the store the consumer's
  loader reads."
  "outbound-payments")

(defn- outbound-payment
  "An outbound payment as `payment.store` leaves it once the scheme has
  settled it."
  [bank-id payment-id status]
  (let [now (utility/now)]
    {:payment-id payment-id
     :idempotency-key (str "ik-" payment-id)
     :scheme-type :scheme-type-fps
     :debtor-account-id "acc.events"
     :creditor-bban "04000412345678"
     :creditor-name "Arthur Dent"
     :currency "GBP"
     :amount 2500
     :status status
     :transaction-id "txn.events"
     :reference "Towel"
     :bank-id bank-id
     :business-day 20260101
     :created-at now
     :created-by {:kind :actor-kind-operator :principal-id "queenswood-admin"}
     :updated-at now}))

(defn- seed-outbound
  [config payment]
  (fdb/transact config
                (fn [txn]
                  (fdb/save-record (fdb/open txn outbound-store)
                                   (schema/OutboundPayment->java payment))
                  nil)
                :test/seed
                "Failed to seed the payment"))

(defn- outbound-envelope
  [sys
   {:keys [event-id bank-id payment-id status-before status-after
           change-kind]}]
  (let [schemas (system/instance sys [:avro :serde])
        payload (avro/serialize (get schemas outbound-event-name)
                                {:bank-id bank-id
                                 :payment-id payment-id
                                 :status-before status-before
                                 :status-after status-after
                                 :change-kind change-kind})]
    (if (error/anomaly? payload)
      payload
      {:id event-id
       :event outbound-event-name
       :payload payload
       :causation-id payment-id
       :correlation-id nil})))

(deftest an-outbound-payment-settling-is-told-and-its-submission-is-not-test
  (with-test-system
   [sys config-file]
   (let [config (processor-config sys)
         bank-id "bnk.events.pay"
         payment-id (utility/generate-id "pmt")
         suffix (str (utility/uuidv7))]
     (nom-test> [_ (seed-outbound config
                                  (outbound-payment
                                   bank-id
                                   payment-id
                                   :outbound-payment-status-completed))
                 _ (store/save-endpoint config
                                        (endpoint
                                         bank-id
                                         (str "whe.p." suffix)
                                         :webhook-endpoint-status-enabled
                                         [outbound-kind]))
                 submitted (outbound-envelope
                            sys
                            {:event-id (str "evt.submit." suffix)
                             :bank-id bank-id
                             :payment-id payment-id
                             :status-before nil
                             :status-after :outbound-payment-status-pending
                             :change-kind :outbound-payment-change-kind-submit})
                 settled (outbound-envelope
                          sys
                          {:event-id (str "evt.settle." suffix)
                           :bank-id bank-id
                           :payment-id payment-id
                           :status-before :outbound-payment-status-pending
                           :status-after :outbound-payment-status-completed
                           :change-kind :outbound-payment-change-kind-settle})
                 _ (testing "the submission has no entry, and writes nothing"
                     (let [result (consume sys submitted)]
                       (is (not (error/anomaly? result)))
                       (nom-test> [written (notifications config bank-id)
                                   _ (is (= 0 (count written)))])))
                 _ (testing "the settlement writes one notification"
                     (is (not (error/anomaly? (consume sys settled)))))
                 written (notifications config bank-id)
                 _ (is (= 1 (count written)))
                 body (body->map (first written))
                 _ (testing "carrying the payment as its read route returns it"
                     (is (= outbound-kind (:kind body)))
                     (is (= "settle" (:change-kind body)))
                     (is (= "OutboundPayment" (:resource-type body)))
                     (is (= payment-id (:resource-id body)))
                     (is (= "pending" (:status-before body)))
                     (is (= "completed" (:status-after body)))
                     (is (= "completed" (get-in body [:data :payment-status])))
                     (is (= "fps" (get-in body [:data :scheme])))
                     (is (= (str "ik-" payment-id) (:idempotency-key body)))
                     (is (not (contains? (:data body) :idempotency-key))))
                 _ (testing "and one delivery to the endpoint that chose it"
                     (nom-test> [chosen (deliveries config
                                                    (str "whe.p." suffix))
                                 _ (is (= 1 (count chosen)))]))]))))

(def ^:private reward-event-name "reward-status-changed")

(def ^:private reward-kind "reward.paid")

(def ^:private rewards-store
  "Must match `reward.store`'s store name."
  "rewards")

(defn- reward
  "A reward as `reward.store` leaves it once paid."
  [bank-id reward-id]
  (let [now (utility/now)]
    {:bank-id bank-id
     :reward-id reward-id
     :account-id "acc.events.rewarded"
     :party-id "pty.events"
     :product-id "prd.events"
     :version-id "prv.events"
     :kind :reward-kind-opening
     :amount 5000
     :currency "GBP"
     :status :reward-status-paid
     :transaction-id "txn.events.reward"
     :run-id "run.events"
     :paid-at now
     :created-at now
     :updated-at now}))

(defn- seed-reward
  [config reward]
  (fdb/transact config
                (fn [txn]
                  (fdb/save-record (fdb/open txn rewards-store)
                                   (schema/Reward->java reward))
                  nil)
                :test/seed
                "Failed to seed the reward"))

(defn- reward-envelope
  [sys
   {:keys [event-id bank-id reward-id change-kind status-before
           status-after]}]
  (let [schemas (system/instance sys [:avro :serde])
        payload (avro/serialize (get schemas reward-event-name)
                                {:bank-id bank-id
                                 :reward-id reward-id
                                 :account-id "acc.events.rewarded"
                                 :status-before status-before
                                 :status-after status-after
                                 :change-kind change-kind})]
    (if (error/anomaly? payload)
      payload
      {:id event-id
       :event reward-event-name
       :payload payload
       :causation-id reward-id
       :correlation-id nil})))

(deftest a-reward-is-told-once-paid-and-never-deferred-test
  (with-test-system
   [sys config-file]
   (let [config (processor-config sys)
         bank-id "bnk.events.reward"
         reward-id (utility/generate-id "rwd")
         suffix (str (utility/uuidv7))]
     (nom-test> [_ (seed-reward config (reward bank-id reward-id))
                 _ (store/save-endpoint config
                                        (endpoint
                                         bank-id
                                         (str "whe.r." suffix)
                                         :webhook-endpoint-status-enabled
                                         [reward-kind]))
                 deferred (reward-envelope
                           sys
                           {:event-id (str "evt.reward.defer." suffix)
                            :bank-id bank-id
                            :reward-id reward-id
                            :change-kind :reward-change-kind-defer
                            :status-before nil
                            :status-after :reward-status-due})
                 _ (is (not (error/anomaly? (consume sys deferred))))
                 none (notifications config bank-id)
                 _ (testing "a defer is acknowledged and writes nothing"
                     (is (empty? none)))
                 paid (reward-envelope sys
                                       {:event-id (str "evt.reward.pay." suffix)
                                        :bank-id bank-id
                                        :reward-id reward-id
                                        :change-kind :reward-change-kind-pay
                                        :status-before :reward-status-due
                                        :status-after :reward-status-paid})
                 _ (is (not (error/anomaly? (consume sys paid))))
                 written (notifications config bank-id)
                 _ (is (= 1 (count written)))
                 body (body->map (first written))
                 _ (testing "carrying the reward as its resource is published"
                     (is (= reward-kind (:kind body)))
                     (is (= "pay" (:change-kind body)))
                     (is (= "Reward" (:resource-type body)))
                     (is (= reward-id (:resource-id body)))
                     (is (= "due" (:status-before body)))
                     (is (= "paid" (:status-after body)))
                     (is (= 5000 (get-in body [:data :amount])))
                     (is (= "acc.events.rewarded"
                            (get-in body [:data :account-id])))
                     (is (= "paid" (get-in body [:data :status])))
                     ;; A row a job paid still carries its run; the
                     ;; deprecated field is not published.
                     (is (not (contains? (:data body) :run-id))))
                 _ (testing "and one delivery to the endpoint that chose it"
                     (nom-test> [chosen (deliveries config
                                                    (str "whe.r." suffix))
                                 _ (is (= 1 (count chosen)))]))]))))
