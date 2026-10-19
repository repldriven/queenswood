(ns com.repldriven.queenswood.cash-account.events-test
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.cash-account.changelog :as changelog]
    [com.repldriven.queenswood.cash-account.core :as core]
    [com.repldriven.queenswood.cash-account.store :as store]

    [com.repldriven.queenswood.cash-account-query.interface :as q]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]))

(def ^:private test-bank-id "bnk_events_test")

(defn- account
  [account-id status]
  {:bank-id test-bank-id
   :account-id account-id
   :party-id "pty.test"
   :product-id "prd.test"
   :version-id "v1"
   :version-from-on 20089
   :created-by {:kind :actor-kind-operator :principal-id "test"}
   :account-type :account-type-personal
   :product-type :account-product-type-sub-ledger-current
   :name "Event Redelivery Test Account"
   :currency "GBP"
   :status status
   :created-at (utility/now)
   :updated-at (utility/now)})

(defn- close-operation
  [account-id operation-id]
  {:bank-id test-bank-id
   :account-id account-id
   :operation-id operation-id
   :kind :cash-account-operation-kind-close
   :status :cash-account-operation-status-pending
   :requested-on 20500
   :account-status :cash-account-status-opened
   :closure-interest {:kind {:pay {}}}
   :created-at (utility/now)
   :created-by {:kind :actor-kind-operator :principal-id "test"}})

(deftest a-paid-closure-closes-an-account-no-provider-holds-test
  (with-test-system
   [sys "classpath:cash-account/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}
         account-id "acc.events.1"
         operation-id "cao.events.1"
         paid {:bank-id test-bank-id
               :account-id account-id
               :operation-id operation-id
               :status-after :account-closure-status-paid}]
     (testing "seed a closing account and its pending close"
       (nom-test> [_ (store/save-account
                      config
                      (account account-id :cash-account-status-closing)
                      {:account-id account-id
                       :status-after :cash-account-status-closing
                       :change-kind :cash-account-change-kind-close})
                   _ (store/save-operation config
                                           (close-operation account-id
                                                            operation-id)
                                           nil)]))
     (testing "the closure's payment closes it and completes its operation"
       (nom-test> [_ (core/closure-paid config paid)
                   found (q/find-account config test-bank-id account-id)
                   _ (is (= :cash-account-status-closed (:status found)))
                   operation (q/find-operation config test-bank-id operation-id)
                   _ (is (= :cash-account-operation-status-completed
                            (:status operation)))
                   _ (is (= {:kind {:pay {}}} (:closure-interest operation)))]))
     (testing
       "redelivered, it finds the close no longer pending and leaves it
              as it is"
       (nom-test> [result (core/closure-paid config paid)
                   _ (is (nil? result))])))))

(deftest a-refused-closure-returns-the-account-test
  (with-test-system
   [sys "classpath:cash-account/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}
         account-id "acc.events.4"
         operation-id "cao.events.4"]
     (nom-test> [_ (store/save-account
                    config
                    (account account-id :cash-account-status-closing)
                    {:account-id account-id
                     :status-after :cash-account-status-closing
                     :change-kind :cash-account-change-kind-close})
                 _ (store/save-operation config
                                         (close-operation account-id
                                                          operation-id)
                                         nil)
                 _ (core/closure-refused config
                                         {:bank-id test-bank-id
                                          :account-id account-id
                                          :operation-id operation-id
                                          :status-after
                                          :account-closure-status-refused
                                          :refused-reason "Over the limit"})
                 found (q/find-account config test-bank-id account-id)
                 _ (is (= :cash-account-status-opened (:status found)))
                 operation (q/find-operation config test-bank-id operation-id)
                 _ (is (= :cash-account-operation-status-refused
                          (:status operation)))
                 _ (is (= "Over the limit" (:refused-reason operation)))]))))

(def ^:private payload-schema
  (delay (avro/json->schema
          (slurp
           (io/resource
            "schemas/cash-account/cash-account-status-changed.avsc.json")))))

(defn- entry
  "The changelog map `store/save-account` hands to `status-changed`,
  with the `:bank-id` and `:updated-at` the store assocs off the record."
  [account-id change-kind updated-at]
  {:bank-id test-bank-id
   :account-id account-id
   :status-before :cash-account-status-opening
   :status-after :cash-account-status-opened
   :change-kind change-kind
   :updated-at updated-at})

(deftest changelog-carries-the-shared-envelope-test
  (testing
    "a status transition serialises as a ChangelogEvent the
           generic relay can decode without knowing this domain"
    (nom-test> [bytes
                (changelog/account-changed
                 (entry "acc.events.2" :cash-account-change-kind-open 1000))
                decoded (schema/pb->ChangelogEvent bytes)
                _ (is (= "cash-account-status-changed" (:event-name decoded)))
                _ (is (seq (:event-id decoded))
                      "an event-id is minted for dedup")
                _ (is (pos? (count (:payload decoded)))
                      "the Avro payload is carried")
                payload (avro/deserialize-same @payload-schema
                                               (:payload decoded))
                _
                (testing "the payload names the write, not just the status"
                  (is (= :cash-account-change-kind-open (:change-kind payload)))
                  (is (= :cash-account-status-opened (:status-after payload))))])))

(deftest dedup-key-distinguishes-two-writes-to-one-account-test
  (testing
    "the kind and the saved record's :updated-at are what separate
           two rotations of one account, which leave the status alone"
    (nom-test> [first-bytes (changelog/account-changed
                             (entry "acc.events.3"
                                    :cash-account-change-kind-rotate-address
                                    1000))
                second-bytes (changelog/account-changed
                              (entry "acc.events.3"
                                     :cash-account-change-kind-rotate-address
                                     2000))
                first-key (:dedup-key (schema/pb->ChangelogEvent first-bytes))
                second-key (:dedup-key (schema/pb->ChangelogEvent second-bytes))
                _ (is
                   (=
                    "acc.events.3:cash-account-change-kind-rotate-address:1000"
                    first-key))
                _ (is (not= first-key second-key))]))
  (testing "a rotation and a migration of one account differ by kind"
    (nom-test> [rotated (changelog/account-changed
                         (entry "acc.events.4"
                                :cash-account-change-kind-rotate-address
                                1000))
                migrated
                (changelog/account-changed
                 (entry "acc.events.4" :cash-account-change-kind-migrate 1000))
                _ (is (not= (:dedup-key (schema/pb->ChangelogEvent rotated))
                            (:dedup-key (schema/pb->ChangelogEvent migrated))))])))

(deftest changelog-without-a-change-kind-fails-the-write-test
  (testing
    "a caller that omits the kind gets an error anomaly, so the
           save fails rather than writing a null a consumer cannot read"
    (let [result (changelog/account-changed
                  (dissoc (entry "acc.events.5" nil 1000) :change-kind))]
      (is (error/error? result))
      (is (= :cash-account/changelog (error/kind result))))))
