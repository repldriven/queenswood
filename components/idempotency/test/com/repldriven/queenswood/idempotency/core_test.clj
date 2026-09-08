(ns ^:eftest/synchronized com.repldriven.queenswood.idempotency.core-test
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.idempotency.core :as SUT]
    [com.repldriven.queenswood.idempotency.store :as store]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(def ^:private principal "bnk.test")
(def ^:private operation "POST /v1/cash-accounts")
(def ^:private fingerprint "fp-first")
(def ^:private other-fingerprint "fp-second")

(defn- config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(defn- claim
  ([config key] (claim config key fingerprint))
  ([config key fp] (SUT/claim-or-replay config principal operation key fp)))

(def ^:private minute-ms (* 60 1000))
(def ^:private day-ms (* 24 60 60 1000))

(deftest claim-or-replay-outcomes-test
  (with-test-system
   [sys "classpath:idempotency/application-test.yml"]
   (let [config (config sys)]
     (testing "a key nobody holds is claimed"
       (is (= ::SUT/claimed (:type (claim config "idem-claim-000000000001")))))
     (testing "the same key while the first is in flight is refused"
       (is (= ::SUT/in-flight
              (:type (claim config "idem-claim-000000000001")))))
     (testing "the same key after completion is replayed, body and status"
       (let [key "idem-claim-000000000002"]
         (is (= ::SUT/claimed (:type (claim config key))))
         (nom-test> [_ (SUT/complete config
                                     principal
                                     operation
                                     key
                                     fingerprint
                                     {:status 200
                                      :body {:account-status
                                             :cash-account-status-opened}})])
         (let [result (claim config key)]
           (is (= ::SUT/completed (:type result)))
           (is (= 200 (:status result)))
           ;; EDN, not JSON: a keyword survives the round trip, which
           ;; is why the body is stored as EDN at all.
           (is (= {:account-status :cash-account-status-opened}
                  (:body result))))))
     (testing "a released claim is claimable again"
       (let [key "idem-claim-000000000003"]
         (is (= ::SUT/claimed (:type (claim config key))))
         (nom-test> [_ (SUT/release config principal operation key)])
         (is (= ::SUT/claimed (:type (claim config key)))))))))

(deftest reclaim-test
  (with-test-system
   [sys "classpath:idempotency/application-test.yml"]
   (let [config (config sys)
         now (utility/now)]
     (testing "a pending entry older than the stale timeout is reclaimed"
       (let [key "idem-stale-000000000001"]
         (nom-test> [_ (store/save config
                                   {:principal-id principal
                                    :operation operation
                                    :idempotency-key key
                                    :state "pending"
                                    :fingerprint fingerprint
                                    ;; A second past the 60s timeout.
                                    :created-at (- now minute-ms 1000)
                                    :expires-at (+ now day-ms)})])
         (is (= ::SUT/claimed (:type (claim config key))))))
     (testing "a completed entry past its expiry is reclaimed"
       (let [key "idem-expired-00000000001"]
         (nom-test> [_ (store/save config
                                   {:principal-id principal
                                    :operation operation
                                    :idempotency-key key
                                    :state "completed"
                                    :status 200
                                    :body (pr-str {:ok true})
                                    :fingerprint fingerprint
                                    :created-at (- now day-ms)
                                    :expires-at (- now 1000)})])
         (is (= ::SUT/claimed (:type (claim config key)))))))))

(deftest fingerprint-mismatch-test
  (with-test-system
   [sys "classpath:idempotency/application-test.yml"]
   (let [config (config sys)]
     (testing "a live pending entry claimed for another request is a mismatch"
       (let [key "idem-mismatch-000000001"]
         (is (= ::SUT/claimed (:type (claim config key))))
         (is (= ::SUT/mismatch (:type (claim config key other-fingerprint))))))
     (testing "a live completed entry answers the same way"
       (let [key "idem-mismatch-000000002"]
         (is (= ::SUT/claimed (:type (claim config key))))
         (nom-test> [_ (SUT/complete config
                                     principal
                                     operation
                                     key
                                     fingerprint
                                     {:status 200 :body {:ok true}})])
         (is (= ::SUT/completed (:type (claim config key))))
         (is (= ::SUT/mismatch (:type (claim config key other-fingerprint))))))
     (testing "an entry written before the field matches anything"
       (let [key "idem-no-fingerprint-001"
             now (utility/now)]
         (nom-test> [_ (store/save config
                                   {:principal-id principal
                                    :operation operation
                                    :idempotency-key key
                                    :state "completed"
                                    :status 200
                                    :body (pr-str {:ok true})
                                    :created-at now
                                    :expires-at (+ now day-ms)})])
         (is (= ::SUT/completed
                (:type (claim config key other-fingerprint)))))))))
