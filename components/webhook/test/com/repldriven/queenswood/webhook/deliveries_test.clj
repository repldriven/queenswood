(ns com.repldriven.queenswood.webhook.deliveries-test
  "The two delivery-side rules that need a delivered delivery to be
  visible: what a re-enable asking for the gap backfills, and what a
  window re-sends (AC-18).

  The surface itself — a test notification, a re-send, the history's
  filters — is asserted through `interface-test`. Here the store is
  written to directly, because only the runner makes a delivery
  delivered and this is not a test of the runner."
  (:require
    [com.repldriven.queenswood.webhook.test-system]

    [com.repldriven.queenswood.webhook.core :as SUT]

    [com.repldriven.queenswood.webhook.store :as store]

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
  [{:enabled true
    :capabilities [{:effect :effect-allow :kind {:webhook-endpoint {}}}]}])

(def ^:private opts {:policies allow-manage})

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

(deftest enabling-since-backfills-only-what-was-never-delivered-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.backfill"]
     (nom-test> [registered
                 (SUT/register config bank-id (endpoint-data "ik-bf") opts)
                 id (:endpoint-id registered)
                 delivered (SUT/test-notification config bank-id id opts)
                 missed (SUT/test-notification config bank-id id opts)
                 _ (mark-delivered config delivered)
                 _ (SUT/disable config bank-id id opts)
                 since (min (:created-at delivered) (:created-at missed))
                 resumed
                 (SUT/enable config bank-id id (assoc opts :since since))
                 _ (testing "the endpoint is enabled again"
                     (is (= :webhook-endpoint-status-enabled
                            (:status resumed))))
                 _ (testing
                     "the notification that was never delivered gets another"
                     (is (= 2
                            (count (deliveries-of config
                                                  bank-id
                                                  id
                                                  (:notification-id missed))))))
                 _ (testing "and the one already delivered gets none"
                     (is (= 1
                            (count (deliveries-of config
                                                  bank-id
                                                  id
                                                  (:notification-id
                                                   delivered))))))]))))

(deftest enabling-without-since-backfills-nothing-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.no.backfill"]
     (nom-test> [registered
                 (SUT/register config bank-id (endpoint-data "ik-no-bf") opts)
                 id (:endpoint-id registered)
                 sent (SUT/test-notification config bank-id id opts)
                 _ (SUT/disable config bank-id id opts)
                 _ (SUT/enable config bank-id id opts)
                 _ (testing
                     "resuming without a since asks for nothing behind it"
                     (is (= 1
                            (count (deliveries-of config
                                                  bank-id
                                                  id
                                                  (:notification-id sent))))))]))))

(deftest a-window-resends-what-it-covers-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.resend.window"]
     (nom-test> [registered
                 (SUT/register config bank-id (endpoint-data "ik-window") opts)
                 id (:endpoint-id registered)
                 delivered (SUT/test-notification config bank-id id opts)
                 missed (SUT/test-notification config bank-id id opts)
                 _ (mark-delivered config delivered)
                 covering (SUT/resend-window config bank-id id {:from 0} opts)
                 _ (testing "a window re-sends what was delivered as well"
                     (is (= 2 (count (:deliveries covering))))
                     (is (= 2
                            (count (deliveries-of config
                                                  bank-id
                                                  id
                                                  (:notification-id
                                                   delivered))))))
                 past (SUT/resend-window config
                                         bank-id
                                         id
                                         {:from (inc (max
                                                      (:created-at delivered)
                                                      (:created-at missed)))}
                                         opts)
                 _ (testing "and a window covering nothing re-sends nothing"
                     (is (empty? (:deliveries past))))]))))
