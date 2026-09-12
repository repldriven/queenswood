(ns ^:eftest/synchronized com.repldriven.queenswood.webhook.store-test
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
   :updated-at 1700000000000})

(defn- notification
  [bank-id notification-id changelog-event-id created-at]
  {:bank-id bank-id
   :notification-id notification-id
   :kind "cash-account.opened"
   :change-kind "open"
   :resource-type "CashAccount"
   :resource-id "acc.1"
   :occurred-at 1700000000000
   :body (.getBytes "{\"id\":\"acc.1\"}" "UTF-8")
   :changelog-event-id changelog-event-id
   :created-at created-at})

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
           :created-at created-at}
          next-attempt-at
          (assoc :next-attempt-at next-attempt-at)))

(defn- attempt
  [bank-id attempt-id delivery-id attempted-at]
  {:bank-id bank-id
   :attempt-id attempt-id
   :delivery-id delivery-id
   :attempted-at attempted-at
   :response-status 200
   :duration-ms 42})

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
                 by-event (SUT/find-notification-by-changelog-event-id config
                                                                       "evt-2")
                 _ (testing "the unique changelog-event-id index finds it"
                     (is (= "whn.2" (:notification-id by-event))))
                 by-bank (SUT/find-notifications-by-bank config bank-id)
                 _ (testing "the bank-and-created index lists them in order"
                     (is (= ["whn.1" "whn.2"]
                            (mapv :notification-id by-bank))))]))))

(deftest a-taken-changelog-event-id-refuses-the-second-write-test
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
                 by-endpoint (SUT/find-deliveries-by-endpoint config "whe.1")
                 _ (testing "the endpoint-and-time index answers the history"
                     (is (= ["whd.1" "whd.2"] (mapv :delivery-id by-endpoint))))
                 _ (SUT/save-attempt
                    config
                    (attempt bank-id "wha.1" "whd.1" 1700000000000))
                 _ (SUT/save-attempt
                    config
                    (attempt bank-id "wha.2" "whd.1" 1700000000001))
                 _ (SUT/save-attempt
                    config
                    (attempt bank-id "wha.3" "whd.2" 1700000000002))
                 one-attempt (SUT/find-attempt config bank-id "wha.1")
                 _ (testing "the attempt round-trips what the call answered"
                     (is (= "whd.1" (:delivery-id one-attempt)))
                     (is (= 200 (:response-status one-attempt)))
                     (is (= 42 (:duration-ms one-attempt))))
                 attempts (SUT/find-attempts-by-delivery config "whd.1")
                 _ (testing "the delivery index holds every attempt, in order"
                     (is (= ["wha.1" "wha.2"] (mapv :attempt-id attempts))))]))))

(deftest a-pending-backlog-does-not-starve-the-reclaim-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.store.claim"
         now 1700000000100
         stranded (assoc (delivery bank-id
                                   "whd.stranded"
                                   "whe.stranded"
                                   :webhook-delivery-status-in-flight
                                   1700000000000 1700000000000)
                         :claim-lease-expires-at (dec now)
                         :claimed-by "runner.died")]
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
                 claimed (SUT/claim-due-deliveries config
                                                   {:now now
                                                    :claimed-by "runner.live"
                                                    :lease-ms 60000
                                                    :limit 2
                                                    :per-endpoint-limit 1})
                 _ (testing "a batch the pending rows could fill on their own"
                     (is (= 2 (count claimed))))
                 _ (testing "still carries the row whose lease expired"
                     (is (contains? (set (mapv :delivery-id claimed))
                                    "whd.stranded")))]))))
