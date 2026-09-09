(ns ^:eftest/synchronized
    com.repldriven.queenswood.idempotency.interceptors-test
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.idempotency.core :as core]
    [com.repldriven.queenswood.idempotency.interceptors :as SUT]
    [com.repldriven.queenswood.idempotency.store :as store]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]

    [sieppari.core :as sieppari]

    [clojure.test :refer [deftest is testing]]
    [clojure.tools.logging.test :as log-test])
  (:import
    (java.util.concurrent CountDownLatch)))

(def ^:private principal "bnk.test")
(def ^:private template "/v1/cash-accounts")
(def ^:private operation (str "POST " template))

(defn- config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(defn- request
  ([config key] (request config key {:name "First"}))
  ([config key body]
   (merge config
          {:request-method :post
           :uri template
           :reitit.core/match {:template template}
           :headers {"idempotency-key" key}
           :auth {:principal-id principal}
           :body-params body})))

(defn- responding
  "A handler counting its own invocations."
  [calls]
  (fn [_request] (swap! calls inc) {:status 200 :body {:name "First"}}))

(defn- run
  [request handler]
  (sieppari/execute [SUT/cache-response handler] request))

(defn- logged?
  [needle]
  (some (fn [{:keys [level message]}]
          (and (= :error level) (.contains ^String (str message) needle)))
        (log-test/the-log)))

;; `with-redefs` alters a root binding, so a stub installed here is
;; visible to every namespace running beside this one. The stubs below
;; therefore consult a thread-local: another thread sees the stub, finds
;; no instruction in it, and gets the real function back.
(def ^:private ^:dynamic *stub* nil)

(def ^:private real-lookup store/lookup)
(def ^:private real-transact store/transact)
(def ^:private real-save store/save)

(def ^:private timeout
  (error/fail :fdb/timeout {:message "Transaction timed out"}))

(defn- stubbed-lookup
  [& args]
  (if (= :lookup *stub*) timeout (apply real-lookup args)))

(defn- stubbed-transact
  [& args]
  (if (= :transact *stub*) timeout (apply real-transact args)))

(defn- stubbed-save
  [txn-or-config entry]
  (if (and (= :complete *stub*) (= "completed" (:state entry)))
    timeout
    (real-save txn-or-config entry)))

(deftest replay-marks-the-response-test
  (with-test-system
   [sys "classpath:idempotency/application-test.yml"]
   (let [config (config sys)
         key "idem-icept-replay-0001"
         calls (atom 0)
         handler (responding calls)
         first-response (run (request config key) handler)
         replay (run (request config key) handler)]
     (testing "the first response is the handler's, unmarked"
       (is (= 200 (:status first-response)))
       (is (nil? (get-in first-response [:headers "Idempotent-Replayed"]))))
     (testing "the second is the cached one, marked as a replay"
       (is (= 200 (:status replay)))
       (is (= {:name "First"} (:body replay)))
       (is (= "true" (get-in replay [:headers "Idempotent-Replayed"]))))
     (testing "the handler ran once" (is (= 1 @calls))))))

(deftest in-flight-is-409-test
  (with-test-system
   [sys "classpath:idempotency/application-test.yml"]
   (let [config (config sys)
         key "idem-icept-inflight-01"
         calls (atom 0)
         release (CountDownLatch. 1)
         entered (CountDownLatch. 1)
         handler (fn [_request]
                   (swap! calls inc)
                   (.countDown entered)
                   (.await release)
                   {:status 200 :body {:name "First"}})
         ;; The first request is held inside the handler, so the
         ;; second meets a claim that is genuinely in flight rather
         ;; than one that happens to still be there.
         held (future (run (request config key) handler))]
     (.await entered)
     (let [second-response (run (request config key) handler)]
       (.countDown release)
       (testing "the second request is refused while the first is in flight"
         (is (= 409 (:status second-response)))
         (is (= "mono/idempotent-request-in-flight"
                (get-in second-response [:body :type]))))
       (testing "the first completes normally and the handler ran once"
         (is (= 200 (:status @held)))
         (is (= 1 @calls)))))))

(deftest key-reused-for-another-request-test
  (with-test-system
   [sys "classpath:idempotency/application-test.yml"]
   (let [config (config sys)
         key "idem-icept-reused-0001"
         calls (atom 0)
         handler (responding calls)
         _ (run (request config key {:name "First"}) handler)
         reused (run (request config key {:name "Second"}) handler)]
     (testing "a different body under a live key is refused 422"
       (is (= 422 (:status reused)))
       (is (= "mono/idempotency-key-reused" (get-in reused [:body :type]))))
     (testing "the handler did not run a second time" (is (= 1 @calls))))))

(deftest cache-unreadable-is-503-test
  (with-test-system
   [sys "classpath:idempotency/application-test.yml"]
   (let [config (config sys)]
     (testing
       "a lookup that answers an anomaly is 503, and the handler
              does not run"
       ;; The anomaly is a *value* here, not a throw: this is the case
       ;; that used to reach `:else`, write a pending marker and run
       ;; the handler on a cache it could not read.
       (log-test/with-log
        (let [calls (atom 0)
              handler (responding calls)]
          (with-redefs [store/lookup stubbed-lookup]
            (binding [*stub* :lookup]
              (let [response (run (request config "idem-icept-lookup-001")
                                  handler)]
                (is (= 503 (:status response)))
                (is (= "mono/idempotency-cache-unavailable"
                       (get-in response [:body :type])))
                (is (zero? @calls))
                (is (logged? "idempotency cache unavailable"))
                ;; AC-4 asks that the entry name the key, not merely
                ;; that something was logged: an operator holding a
                ;; client's key and no way to find its line is back to
                ;; reading the whole log.
                (is (logged? "idem-icept-lookup-001"))))))))
     (testing "a transaction that answers an anomaly is 503 the same way"
       (log-test/with-log
        (let [calls (atom 0)
              handler (responding calls)]
          (with-redefs [store/transact stubbed-transact]
            (binding [*stub* :transact]
              (let [response (run (request config "idem-icept-txn-retry")
                                  handler)]
                (is (= 503 (:status response)))
                (is (zero? @calls))
                (is (logged? "idempotency cache unavailable"))
                (is (logged? "idem-icept-txn-retry")))))))))))

(deftest failed-completion-returns-the-handlers-response-test
  (with-test-system
   [sys "classpath:idempotency/application-test.yml"]
   (let [config (config sys)
         key "idem-icept-complete-01"
         calls (atom 0)
         handler (responding calls)]
     (log-test/with-log
      (with-redefs [store/save stubbed-save]
        (binding [*stub* :complete]
          (let [response (run (request config key) handler)]
            (testing "the handler's effect committed, so its answer stands"
              (is (= 200 (:status response)))
              (is (= {:name "First"} (:body response))))
            (testing "the failure is logged, and names the key"
              (is (logged? "idempotency completion failed"))
              (is (logged? key)))))))
     (testing "the claim was released, so a retry re-runs the handler"
       (is (= ::core/claimed
              (:type (core/claim-or-replay config
                                           principal
                                           operation
                                           key
                                           "any-fingerprint"))))))))

(deftest thrown-handler-leaves-no-claim-test
  (with-test-system
   [sys "classpath:idempotency/application-test.yml"]
   (let [config (config sys)
         key "idem-icept-threw-00001"
         handler (fn [_request]
                   ;; nosemgrep: no-raw-throw
                   (throw (ex-info "handler blew up" {})))]
     (testing "the throw propagates"
       (is (thrown? Exception (run (request config key) handler))))
     (testing "and the claim is gone, so the next request runs the handler"
       (is (= ::core/claimed
              (:type (core/claim-or-replay config
                                           principal
                                           operation
                                           key
                                           "any-fingerprint"))))))))
