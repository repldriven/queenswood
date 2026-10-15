(ns com.repldriven.queenswood.webhook.deliveries-test
  "The delivery-side rules that need deliveries already on record: what
  a re-enable asking for the gap backfills, what a window re-sends
  (AC-18), and the daily limit on test notifications.

  The surface itself — a test notification, a re-send, the history's
  filters — is asserted through `interface-test`. Here the store is
  written to directly, because only the runner makes a delivery
  delivered and this is not a test of the runner."
  (:require
    [com.repldriven.queenswood.webhook.test-system]

    [com.repldriven.queenswood.webhook.core :as SUT]

    [com.repldriven.queenswood.webhook.store :as store]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.test :refer [deftest is testing]]))

(defn- fdb-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(def ^:private config-file "classpath:webhook/application-test.yml")

(def ^:private allow-manage
  [{:status :policy-status-active
    :capabilities [{:effect :effect-allow :kind {:webhook-endpoint {}}}]}])

(def ^:private opts {:policies allow-manage})

(def ^:private actor {:kind :actor-kind-member :principal-id "usr.1"})

(def ^:private tenant-address
  "A public address written as a literal, so registration's own
  resolution never leaves the machine."
  "https://93.184.216.34/hooks")

(defn- endpoint-data
  [idempotency-key]
  {:address tenant-address :idempotency-key idempotency-key})

(defn- deliveries-of
  "Every delivery of `notification-id` the endpoint holds."
  [config bank-id endpoint-id notification-id]
  (let [result (SUT/get-deliveries config bank-id endpoint-id)]
    (filterv #(= notification-id (:notification-id %)) (:deliveries result))))

(defn- mark-delivered
  [config delivery]
  (store/save-delivery config
                       (assoc delivery
                              :status
                              :webhook-delivery-status-delivered)))

(defn- notification
  "A cash account's opening, as the event consumer records one."
  [bank-id notification-id created-at]
  {:bank-id bank-id
   :notification-id notification-id
   :kind "cash-account.opened"
   :change-kind "open"
   :resource-type "CashAccount"
   :resource-id "acc.1"
   :correlation-id "c0ffee00-0000-4000-8000-000000000001"
   :body (.getBytes "{\"id\":\"acc.1\"}" "UTF-8")
   :idempotency-key notification-id
   :created-at created-at
   :created-by {:kind :actor-kind-platform :principal-id "webhook"}})

(defn- delivery
  [notification endpoint-id status created-at]
  {:bank-id (:bank-id notification)
   :delivery-id (str "whd." (:notification-id notification) "." endpoint-id)
   :notification-id (:notification-id notification)
   :endpoint-id endpoint-id
   :status status
   :kind (:kind notification)
   :created-at created-at
   :created-by {:kind :actor-kind-platform :principal-id "webhook"}
   :attempt-count 11})

(deftest enabling-since-backfills-only-what-has-no-delivery-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.backfill"
         held (notification bank-id "whn.held" 1700000000000)
         missed (notification bank-id "whn.missed" 1700000000001)
         before (notification bank-id "whn.before" 1699999999999)]
     (nom-test> [registered (SUT/register config
                                          bank-id
                                          (endpoint-data "ik-bf")
                                          actor
                                          opts)
                 id (:endpoint-id registered)
                 other (SUT/register config
                                     bank-id
                                     (endpoint-data "ik-bf-other")
                                     actor
                                     opts)
                 tested (SUT/test-notification config
                                               bank-id
                                               (:endpoint-id other)
                                               actor
                                               opts)
                 _ (SUT/disable config bank-id id actor opts)
                 _ (store/save-notification config held)
                 _ (store/save-notification config missed)
                 _ (store/save-notification config before)
                 _ (store/save-delivery config
                                        (delivery
                                         held
                                         id
                                         :webhook-delivery-status-failed
                                         1700000000000))
                 resumed (SUT/enable config
                                     bank-id
                                     id
                                     actor
                                     (assoc opts :since 1700000000000))
                 _ (testing "the endpoint is enabled again"
                     (is (= :webhook-endpoint-status-enabled
                            (:status resumed))))
                 _ (testing "the notification it never had gets one"
                     (is (= [:webhook-delivery-status-pending]
                            (mapv
                             :status
                             (deliveries-of config bank-id id "whn.missed")))))
                 _ (testing "the one it holds, even failed, gets no other"
                     (is (= [:webhook-delivery-status-failed]
                            (mapv
                             :status
                             (deliveries-of config bank-id id "whn.held")))))
                 _ (testing "nor one from before `since`"
                     (is (empty?
                          (deliveries-of config bank-id id "whn.before"))))
                 _ (testing "nor another endpoint's test notification"
                     (is (empty? (deliveries-of config
                                                bank-id
                                                id
                                                (:notification-id tested)))))]))))

(deftest enabling-without-since-backfills-nothing-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.no.backfill"]
     (nom-test> [registered (SUT/register config
                                          bank-id
                                          (endpoint-data "ik-no-bf")
                                          actor
                                          opts)
                 id (:endpoint-id registered)
                 _ (SUT/disable config bank-id id actor opts)
                 _ (store/save-notification
                    config
                    (notification bank-id "whn.gap" 1700000000000))
                 _ (SUT/enable config bank-id id actor opts)
                 _ (testing
                     "resuming without a since asks for nothing behind it"
                     (is (empty? (deliveries-of config bank-id id "whn.gap"))))]))))

(deftest a-window-resends-what-failed-in-it-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.resend.window"]
     (nom-test> [registered (SUT/register config
                                          bank-id
                                          (endpoint-data "ik-window")
                                          actor
                                          opts)
                 id (:endpoint-id registered)
                 delivered (SUT/test-notification config bank-id id actor opts)
                 failed (SUT/test-notification config bank-id id actor opts)
                 pending (SUT/test-notification config bank-id id actor opts)
                 _ (mark-delivered config delivered)
                 _ (store/save-delivery config
                                        (assoc failed
                                               :status
                                               :webhook-delivery-status-failed
                                               :attempt-count 11))
                 covering
                 (SUT/resend-window config bank-id id {:from 0} actor opts)
                 _ (testing "a window queues again only what failed"
                     (is (= {:resent-count 1} covering)))
                 resent
                 (store/find-delivery config bank-id (:delivery-id failed))
                 _ (testing "due now, its attempts counted from none"
                     (is (= :webhook-delivery-status-pending (:status resent)))
                     (is (zero? (:attempt-count resent)))
                     (is (some? (:next-attempt-at resent))))
                 _ (testing "and adds no delivery for any of the three"
                     (is (= [1 1 1]
                            (mapv (fn [d]
                                    (count (deliveries-of config
                                                          bank-id
                                                          id
                                                          (:notification-id
                                                           d))))
                                  [delivered failed pending]))))
                 again
                 (SUT/resend-window config bank-id id {:from 0} actor opts)
                 _ (testing "asking again queues nothing"
                     (is (= {:resent-count 0} again)))
                 _ (store/save-delivery
                    config
                    (assoc failed :status :webhook-delivery-status-failed))
                 past (SUT/resend-window config
                                         bank-id
                                         id
                                         {:from (inc (:created-at pending))}
                                         actor
                                         opts)
                 _ (testing "and a window covering nothing queues nothing"
                     (is (= {:resent-count 0} past)))]))))

(def ^:private one-test-a-day
  (conj allow-manage
        {:status :policy-status-active
         :limits [{:kind {:webhook-test-notification {}}
                   :bound {:kind {:max {:aggregate
                                        {:kind {:count
                                                {:value 1
                                                 :window
                                                 :time-window-daily}}}}}}}]}))

(deftest test-notifications-are-held-to-the-daily-limit-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.test.limit"
         limited {:policies one-test-a-day}]
     (nom-test> [registered (SUT/register config
                                          bank-id
                                          (endpoint-data "ik-test-limit")
                                          actor
                                          limited)
                 id (:endpoint-id registered)
                 _ (SUT/test-notification config bank-id id actor limited)
                 _
                 (let [refused
                       (SUT/test-notification config bank-id id actor limited)]
                   (testing "the second of the day is refused"
                     (is (= :policy/limit-exceeded (error/kind refused)))))]))))
