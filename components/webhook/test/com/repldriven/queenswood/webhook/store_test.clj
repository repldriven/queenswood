(ns com.repldriven.queenswood.webhook.store-test
  "The four webhook record types against a real record store (AC-06):
  each one round-trips, every index the record-type YAML declares
  answers, a second write under a taken unique key is refused, and the
  claim scan reaches a row whose lease expired behind a pending
  backlog that could fill the batch on its own.

  The lifecycle and the policy refusals live in `interface-test`; the
  pure rules live in `domain-test`."
  (:require
    [com.repldriven.queenswood.webhook.test-system]

    [com.repldriven.queenswood.webhook.store :as SUT]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.test :refer [deftest is testing]]))

(defn- fdb-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(def ^:private config-file "classpath:webhook/application-test.yml")

(defn- endpoint
  [bank-id endpoint-id idempotency-key]
  {:bank-id bank-id
   :endpoint-id endpoint-id
   :address "https://tenant.example/hooks"
   :description "Round trip"
   :kinds ["cash-account.opened"]
   :status :webhook-endpoint-status-enabled
   :secret "whsec_round_trip"
   :idempotency-key idempotency-key
   :created-at 1700000000000
   :created-by {:kind :actor-kind-member :principal-id "usr.1"}})

(defn- notification
  [bank-id notification-id idempotency-key created-at]
  {:bank-id bank-id
   :notification-id notification-id
   :kind "cash-account.opened"
   :change-kind "open"
   :resource-type "CashAccount"
   :resource-id "acc.1"
   :correlation-id "c0ffee00-0000-4000-8000-000000000001"
   :body (.getBytes "{\"id\":\"acc.1\"}" "UTF-8")
   :idempotency-key idempotency-key
   :created-at created-at
   :created-by {:kind :actor-kind-platform :principal-id "webhook"}})

(defn- delivery
  "A delivery row. `next-attempt-at` is dropped when absent rather than
  written as nil: the proto declares it optional, and protojure
  refuses a nil where it expects an int."
  [bank-id delivery-id endpoint-id status next-attempt-at created-at]
  (cond-> {:bank-id bank-id
           :delivery-id delivery-id
           :notification-id "whn.1"
           :endpoint-id endpoint-id
           :status status
           :kind "cash-account.opened"
           :created-at created-at
           :created-by {:kind :actor-kind-platform :principal-id "webhook"}
           :attempt-count 0}
          next-attempt-at
          (assoc :next-attempt-at next-attempt-at)))

(defn- attempt
  [bank-id attempt-id delivery-id created-at]
  {:bank-id bank-id
   :delivery-id delivery-id
   :attempt-id attempt-id
   :response-status 200
   :duration-ms 42
   :created-at created-at})

(deftest endpoint-round-trips-and-answers-every-index-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.store.endpoints"
         other-bank "bnk.store.endpoints.other"]
     (nom-test> [_ (SUT/save-endpoint config (endpoint bank-id "whe.1" "ik-1"))
                 _ (SUT/save-endpoint config (endpoint bank-id "whe.2" "ik-2"))
                 _ (SUT/save-endpoint config
                                      (endpoint other-bank "whe.3" "ik-1"))
                 loaded (SUT/find-endpoint config bank-id "whe.1")
                 _ (testing "every field survives the round trip"
                     (is (= "whe.1" (:endpoint-id loaded)))
                     (is (= "https://tenant.example/hooks" (:address loaded)))
                     (is (= "Round trip" (:description loaded)))
                     (is (= ["cash-account.opened"] (:kinds loaded)))
                     (is (= :webhook-endpoint-status-enabled (:status loaded)))
                     (is (= "whsec_round_trip" (:secret loaded)))
                     (is (= "ik-1" (:idempotency-key loaded)))
                     (is (= 1700000000000 (:created-at loaded))))
                 missing (SUT/find-endpoint config bank-id "whe.absent")
                 _ (testing "an absent endpoint reads as nil, not a rejection"
                     (is (nil? missing)))
                 listed (SUT/get-endpoints config bank-id)
                 _ (testing "the bank list holds this bank's rows alone"
                     (is (= ["whe.1" "whe.2"]
                            (mapv :endpoint-id (:endpoints listed)))))
                 counted (SUT/count-endpoints config bank-id)
                 _ (testing "the count index counts them"
                     (is (= 2 counted))
                     (is (= 1 (SUT/count-endpoints config other-bank))))
                 by-key
                 (SUT/find-endpoint-by-idempotency-key config bank-id "ik-1")
                 _ (testing "the unique key index finds the right bank's row"
                     (is (= "whe.1" (:endpoint-id by-key))))
                 other-by-key
                 (SUT/find-endpoint-by-idempotency-key config other-bank "ik-1")
                 _ (testing "and the same key under another bank is its own"
                     (is (= "whe.3" (:endpoint-id other-by-key))))]))))

(deftest a-taken-endpoint-key-refuses-the-second-write-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.store.unique"]
     (nom-test> [_ (SUT/save-endpoint config
                                      (endpoint bank-id "whe.1" "ik-taken"))
                 _ (let [refused (SUT/save-endpoint
                                  config
                                  (endpoint bank-id "whe.2" "ik-taken"))]
                     (testing "the unique index refuses it, distinguishably"
                       (is (true? (SUT/uniqueness-violation? refused)))))
                 counted (SUT/count-endpoints config bank-id)
                 _ (testing "and the second row is not there"
                     (is (= 1 counted)))]))))

(deftest notification-round-trips-and-answers-every-index-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.store.notifications"]
     (nom-test> [_ (SUT/save-notification
                    config
                    (notification bank-id "whn.1" "evt-1" 1700000000000))
                 _ (SUT/save-notification
                    config
                    (notification bank-id "whn.2" "evt-2" 1700000000001))
                 loaded (SUT/find-notification config bank-id "whn.1")
                 _ (testing "the stored body survives as bytes"
                     (is (= "cash-account.opened" (:kind loaded)))
                     (is (= "CashAccount" (:resource-type loaded)))
                     (is (= "{\"id\":\"acc.1\"}"
                            (String. ^bytes (:body loaded) "UTF-8"))))
                 by-event (SUT/find-notification-by-idempotency-key config
                                                                    bank-id
                                                                    "evt-2")
                 _ (testing "the unique idempotency-key index finds it"
                     (is (= "whn.2" (:notification-id by-event))))
                 first-page (SUT/find-notifications-since config
                                                          bank-id
                                                          1700000000000
                                                          nil
                                                          1)
                 next-page (SUT/find-notifications-since config
                                                         bank-id
                                                         1700000000000
                                                         (:key (peek
                                                                first-page))
                                                         1)
                 _ (testing "the bank-and-created index pages them in order"
                     (is (= ["whn.1" "whn.2"]
                            (mapv (comp :notification-id :notification)
                                  (into first-page next-page)))))
                 later (SUT/find-notifications-since config
                                                     bank-id
                                                     1700000000001
                                                     nil
                                                     10)
                 _ (testing "and starts at the instant asked for"
                     (is (= ["whn.2"]
                            (mapv (comp :notification-id :notification)
                                  later))))
                 counted (SUT/count-notifications-since config
                                                        bank-id
                                                        "cash-account.opened"
                                                        1700000000001)
                 _ (testing "the kind-and-created index counts from an instant"
                     (is (= 1 counted)))]))))

(deftest a-taken-idempotency-key-refuses-the-second-write-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.store.notifications.unique"]
     (nom-test> [_ (SUT/save-notification
                    config
                    (notification bank-id "whn.1" "evt-taken" 1700000000000))
                 _ (let [refused (SUT/save-notification config
                                                        (notification
                                                         bank-id
                                                         "whn.2"
                                                         "evt-taken"
                                                         1700000000001))]
                     (testing "a redelivered relay event writes no second one"
                       (is (true? (SUT/uniqueness-violation? refused)))))]))))

(deftest delivery-and-attempt-round-trip-and-answer-every-index-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.store.deliveries"]
     (nom-test> [_ (SUT/save-delivery config
                                      (delivery bank-id
                                                "whd.1"
                                                "whe.1"
                                                :webhook-delivery-status-pending
                                                1700000000000 1700000000000))
                 _ (SUT/save-delivery config
                                      (delivery bank-id
                                                "whd.2"
                                                "whe.1"
                                                :webhook-delivery-status-pending
                                                1700000000001 1700000000001))
                 _ (SUT/save-delivery config
                                      (delivery
                                       bank-id
                                       "whd.3"
                                       "whe.2"
                                       :webhook-delivery-status-delivered
                                       nil
                                       1700000000002))
                 loaded (SUT/find-delivery config bank-id "whd.1")
                 _ (testing "the delivery round-trips"
                     (is (= "whn.1" (:notification-id loaded)))
                     (is (= "whe.1" (:endpoint-id loaded)))
                     (is (= :webhook-delivery-status-pending (:status loaded)))
                     (is (= "cash-account.opened" (:kind loaded))))
                 due (SUT/find-deliveries-by-status
                      config
                      :webhook-delivery-status-pending)
                 _ (testing "the status-and-due index answers the runner"
                     (is (= ["whd.1" "whd.2"] (mapv :delivery-id due))))
                 delivered (SUT/find-deliveries-by-status
                            config
                            :webhook-delivery-status-delivered)
                 _ (testing "and separates the statuses"
                     (is (= ["whd.3"] (mapv :delivery-id delivered))))
                 by-endpoint
                 (SUT/find-deliveries-by-endpoint config bank-id "whe.1")
                 _ (testing "the endpoint-and-time index answers the history"
                     (is (= ["whd.1" "whd.2"] (mapv :delivery-id by-endpoint))))
                 _ (SUT/save-delivery config
                                      (delivery bank-id
                                                "whd.4"
                                                "whe.1"
                                                :webhook-delivery-status-failed
                                                nil
                                                1700000000003))
                 failed (SUT/find-failed-deliveries config
                                                    bank-id
                                                    "whe.1" 1700000000000
                                                    1700000000003 10)
                 _ (testing "the endpoint-and-status index finds the failed"
                     (is (= ["whd.4"] (mapv :delivery-id failed))))
                 outside (SUT/find-failed-deliveries config
                                                     bank-id
                                                     "whe.1" 1700000000000
                                                     1700000000002 10)
                 _ (testing "inside the window only" (is (empty? outside)))
                 held (SUT/holds-delivery? config bank-id "whe.2" "whn.1")
                 none (SUT/holds-delivery? config bank-id "whe.3" "whn.1")
                 _ (testing "the endpoint-and-notification index answers"
                     (is (true? held))
                     (is (false? none)))
                 _ (SUT/save-attempt
                    config
                    (attempt bank-id "wha.1" "whd.1" 1700000000000))
                 _ (SUT/save-attempt
                    config
                    (attempt bank-id "wha.2" "whd.1" 1700000000001))
                 _ (SUT/save-attempt
                    config
                    (attempt bank-id "wha.3" "whd.2" 1700000000002))
                 one-attempt (SUT/find-attempt config bank-id "whd.1" "wha.1")
                 _ (testing "the attempt round-trips what the call answered"
                     (is (= "whd.1" (:delivery-id one-attempt)))
                     (is (= 200 (:response-status one-attempt)))
                     (is (= 42 (:duration-ms one-attempt))))
                 attempts (SUT/find-attempts-by-delivery config bank-id "whd.1")
                 _ (testing "a delivery's attempts are read in order"
                     (is (= ["wha.1" "wha.2"] (mapv :attempt-id attempts))))]))))

(deftest a-pending-backlog-does-not-starve-the-reclaim-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.store.claim"
         now 1700000000100
         stranded (delivery bank-id
                            "whd.stranded"
                            "whe.stranded"
                            :webhook-delivery-status-in-flight
                            (dec now)
                            1700000000000)]
     (nom-test> [_ (SUT/save-delivery config stranded)
                 _ (SUT/save-delivery config
                                      (delivery bank-id
                                                "whd.pending.1"
                                                "whe.1"
                                                :webhook-delivery-status-pending
                                                1700000000001 1700000000001))
                 _ (SUT/save-delivery config
                                      (delivery bank-id
                                                "whd.pending.2"
                                                "whe.2"
                                                :webhook-delivery-status-pending
                                                1700000000002 1700000000002))
                 {:keys [claimed]}
                 (SUT/claim-due-deliveries
                  config
                  {:now now :lease-ms 60000 :limit 2 :per-endpoint-limit 1})
                 _ (testing "a batch the pending rows could fill on their own"
                     (is (= 2 (count claimed))))
                 _ (testing "still carries the row whose claim lapsed"
                     (is (contains? (set (mapv :delivery-id claimed))
                                    "whd.stranded")))]))))

(deftest a-held-endpoints-backlog-does-not-starve-another-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.store.held"
         now 1700000001000
         opts {:now now
               :lease-ms 60000
               :limit 1
               :per-endpoint-limit 1
               :endpoint-allowance (fn [_ _ endpoint-id]
                                     (if (= "whe.a.held" endpoint-id) 0 1))}]
     (nom-test> [_ (reduce (fn [_ n]
                             (SUT/save-delivery
                              config
                              (delivery bank-id
                                        (str "whd.held." n)
                                        "whe.a.held"
                                        :webhook-delivery-status-pending
                                        (+ 1700000000000 n)
                                        1700000000000)))
                           nil
                           (range 12))
                 _ (SUT/save-delivery config
                                      (delivery bank-id
                                                "whd.waiting"
                                                "whe.b.waiting"
                                                :webhook-delivery-status-pending
                                                1700000000500 1700000000500))
                 first-pass (SUT/claim-due-deliveries config opts)
                 _ (testing
                     "a pass whose read the held backlog fills claims nothing"
                     (is (empty? (:claimed first-pass)))
                     (is (= [bank-id "whe.a.held"] (:cursor first-pass))))
                 next-pass (SUT/claim-due-deliveries
                            config
                            (assoc opts :cursor (:cursor first-pass)))
                 _ (testing
                     "and the next starts past it, to the endpoint waiting"
                     (is (= ["whd.waiting"]
                            (mapv :delivery-id (:claimed next-pass)))))
                 _ (testing "wrapping round to the start once the index ends"
                     (is (= [bank-id "whe.a.held"] (:cursor next-pass))))]))))
