(ns com.repldriven.queenswood.party.store-test
  "The store side of the create-party idempotency key: the unique
  index the retry hits, and the read-back that turns that violation
  into the original party."
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.party.store :as store]

    [com.repldriven.queenswood.party-query.interface :as q]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(def ^:private test-bank-id "bnk_party_idem_test")
(def ^:private other-bank-id "bnk_party_idem_test_2")

(defn- party
  [bank-id party-id idempotency-key]
  {:bank-id bank-id
   :party-id party-id
   :type :party-type-person
   :display-name "Idempotency Test Party"
   :status :party-status-pending
   :created-at (utility/now)
   :updated-at (utility/now)
   :idempotency-key idempotency-key})

(defn- changelog
  [party-id]
  {:party-id party-id :status-after :party-status-pending})

(deftest idempotency-key-unique-index-and-read-back-test
  (with-test-system
   [sys "classpath:party/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}
         key "party-idem-key-000000000001"]
     (testing "first create with an idempotency-key is saved"
       (nom-test> [_ (store/save-party config
                                       (party test-bank-id "pty.1" key)
                                       (changelog "pty.1"))]))
     (testing
       "a second, different party reusing the same
               [bank-id, idempotency-key] hits the unique index"
       (let [result (store/save-party config
                                      (party test-bank-id "pty.2" key)
                                      (changelog "pty.2"))]
         (is (store/uniqueness-violation? result)
             "duplicate idempotency-key for the same bank must violate")))
     (testing "read-back returns the original party, not the duplicate"
       (nom-test> [found
                   (q/find-party-by-idempotency-key config test-bank-id key)
                   _ (is (= "pty.1" (:party-id found)))
                   _ (is (= key (:idempotency-key found)))]))
     (testing "the same key in another bank is a different party"
       (nom-test> [_ (store/save-party config
                                       (party other-bank-id "pty.3" key)
                                       (changelog "pty.3"))
                   found
                   (q/find-party-by-idempotency-key config other-bank-id key)
                   _ (is (= "pty.3" (:party-id found)))]))
     (testing "a key no party was written under finds nothing"
       (nom-test> [found (q/find-party-by-idempotency-key
                          config
                          test-bank-id
                          "party-idem-key-000000000002")
                   _ (is (nil? found))])))))

(deftest party-without-a-key-writes-no-index-entry-test
  (with-test-system
   [sys "classpath:party/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}]
     (testing
       "two keyless parties — the shape an in-process caller writes —
               both save, so the unique index never sees them"
       (nom-test> [_ (store/save-party
                      config
                      (dissoc (party test-bank-id "pty.bare.1" nil)
                       :idempotency-key)
                      (changelog "pty.bare.1"))
                   _ (store/save-party
                      config
                      (dissoc (party test-bank-id "pty.bare.2" nil)
                       :idempotency-key)
                      (changelog "pty.bare.2"))])))))
