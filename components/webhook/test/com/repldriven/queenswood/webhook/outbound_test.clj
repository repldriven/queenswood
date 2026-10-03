(ns com.repldriven.queenswood.webhook.outbound-test
  "The delivery runner against a receiver this test starts and a real
  record store: the outcomes and their attempt rows (AC-11), the claim
  that makes a second pass send nothing (AC-12), the four bounds on the
  call that can be observed from outside it (AC-13), and the endpoint's
  breaker holding its deliveries through an outage.

  The signing the deliveries carry is asserted in `signing-test`; the
  backoff and the outcome rule in `domain-test`."
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.webhook.outbound :as SUT]

    [com.repldriven.queenswood.webhook.domain :as domain]
    [com.repldriven.queenswood.webhook.store :as store]

    [com.repldriven.queenswood.circuit-breaker.interface :as circuit-breaker]

    [com.repldriven.mono.server.interface :as server]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]])
  (:import
    (java.io ByteArrayInputStream)
    (java.nio.charset StandardCharsets)))

(def ^:private config-file "classpath:webhook/outbound-test.yml")

(def ^:private request-timeout-ms 1000)

(def ^:private max-attempts 11)

(def ^:private delivery-policy
  {:default {:initial-backoff-ms 30000
             :backoff-growth 4
             :max-backoff-ms 14400000
             :max-attempts max-attempts
             :max-age-ms 86400000}
   :breaker {:failure-threshold 3
             :cool-down-ms 30000
             :max-cool-down-ms 3600000
             :probe-lease-ms 60000}})

(def ^:private oversized-body
  "A response body an order of magnitude past the bound, so a runner
  reading it whole would be reading what the bound exists to stop."
  (str/join (repeat (* 10 domain/max-response-bytes) "x")))

(def ^:private reachable
  "The send-time address check, for the cases whose point is what the
  receiver answered. Every address a test can bind to is one the real
  rule refuses, so those cases pass the check and `SUT/address-refusal` is
  asserted on its own below."
  (constantly nil))

(defn- receiver
  "A tenant's endpoint, one path per outcome the runner must handle.
  `seen` collects each request's path and headers, so the signing a
  delivery carried is readable from the receiving side; `/flaky` answers
  503 while `down` holds true."
  ([seen] (receiver seen (atom false)))
  ([seen down]
   (fn [_ctx]
     (fn [{:keys [uri headers]}]
       (swap! seen conj {:uri uri :headers headers})
       (case uri
         "/ok" {:status 200 :body "{}"}
         "/fail" {:status 500 :body "nope"}
         "/flaky" (if @down {:status 503 :body ""} {:status 200 :body "{}"})
         "/slow" (do (Thread/sleep (* 2 request-timeout-ms))
                     {:status 200 :body "{}"})
         "/redirect" {:status 302
                      :headers {"Location" "http://127.0.0.1:1/private"}
                      :body ""}
         "/big" {:status 200 :body oversized-body}
         {:status 404 :body ""})))))

(defn- runner-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])
   :runner-id "runner-test"
   :address-check reachable
   :delivery-policy delivery-policy
   :batch-size 32
   :claim-lease-ms 60000
   :request-timeout-ms request-timeout-ms
   :max-in-flight-per-endpoint 2})

(defn- seed
  "One enabled endpoint pointing at `path` on the receiver, one
  notification, and one delivery due now. Returns the three ids."
  [config base-url path
   {:keys [bank-id attempts status claim-lease-expires-at]}]
  (let [suffix (str (utility/uuidv7))
        endpoint-id (str "whe." suffix)
        notification-id (str "whn." suffix)
        delivery-id (str "whd." suffix)
        now (utility/now)]
    (nom-test>
      [_ (store/save-endpoint config
                              {:bank-id bank-id
                               :endpoint-id endpoint-id
                               :address (str base-url path)
                               :status :webhook-endpoint-status-enabled
                               :secret "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"
                               :idempotency-key (str "ik." suffix)
                               :created-at now
                               :updated-at now})
       _ (store/save-notification config
                                  {:bank-id bank-id
                                   :notification-id notification-id
                                   :kind "cash-account.opened"
                                   :change-kind "open"
                                   :resource-type "CashAccount"
                                   :resource-id "acc.1"
                                   :occurred-at now
                                   :body (.getBytes "{\"id\":\"acc.1\"}"
                                                    StandardCharsets/UTF_8)
                                   :changelog-event-id (str "cle." suffix)
                                   :created-at now})
       _ (store/save-delivery
          config
          (utility/assoc-some
           {:bank-id bank-id
            :delivery-id delivery-id
            :notification-id notification-id
            :endpoint-id endpoint-id
            :status (or status :webhook-delivery-status-pending)
            :kind "cash-account.opened"
            :created-at now}
           :attempts attempts
           :claim-lease-expires-at claim-lease-expires-at
           :claimed-by (when claim-lease-expires-at "runner-that-died")))])
    {:endpoint-id endpoint-id
     :notification-id notification-id
     :delivery-id delivery-id}))

(defn- delivery-of
  [config bank-id delivery-id]
  (store/find-delivery config bank-id delivery-id))

(defn- attempts-of
  [config delivery-id]
  (store/find-attempts-by-delivery config delivery-id))

(deftest delivery-outcomes-test
  (let [seen (atom [])]
    (with-test-system
     [sys
      [config-file
       #(assoc-in % [:system/defs :receiver :handler] (receiver seen))]]
     (let [config (runner-config sys)
           base-url (server/http-local-url
                     (system/instance sys [:receiver :jetty-adapter]))]
       (testing "a 2xx marks the delivery delivered, with one attempt row"
         (let [bank-id "bnk.deliver.ok"
               {:keys [delivery-id]}
               (seed config base-url "/ok" {:bank-id bank-id})]
           (SUT/drain-once config)
           (nom-test> [delivery (delivery-of config bank-id delivery-id)
                       rows (attempts-of config delivery-id)
                       _ (is (= :webhook-delivery-status-delivered
                                (:status delivery)))
                       _ (is (= 1 (:attempts delivery)))
                       _ (is (= 1 (count rows)))
                       _ (is (= 200 (:response-status (first rows))))
                       _ (is (some? (:duration-ms (first rows))))
                       _ (is (str/blank? (:claimed-by delivery))
                             "the claim is released with the outcome")])))
       (testing "a 500 keeps it pending, with the next attempt inside a minute"
         (let [bank-id "bnk.deliver.fail"
               {:keys [delivery-id]}
               (seed config base-url "/fail" {:bank-id bank-id})
               before (utility/now)]
           (SUT/drain-once config)
           (nom-test> [delivery (delivery-of config bank-id delivery-id)
                       rows (attempts-of config delivery-id)
                       _ (is (= :webhook-delivery-status-pending
                                (:status delivery)))
                       _ (is (= 1 (:attempts delivery)))
                       _ (is (= 500 (:last-response-status delivery)))
                       _ (is (< (- (:next-attempt-at delivery) before) 60000))
                       _ (is (= [500] (mapv :response-status rows)))])))
       (testing "the attempt past the schedule fails and keeps every row"
         (let [bank-id "bnk.deliver.spent"
               {:keys [delivery-id]} (seed config
                                           base-url
                                           "/fail"
                                           {:bank-id bank-id
                                            :attempts (dec max-attempts)})]
           (SUT/drain-once config)
           (nom-test> [delivery (delivery-of config bank-id delivery-id)
                       rows (attempts-of config delivery-id)
                       _ (is (= :webhook-delivery-status-failed
                                (:status delivery)))
                       _ (is (= max-attempts (:attempts delivery)))
                       _ (is
                          (zero? (:next-attempt-at delivery))
                          "a failed delivery has no next attempt to be due at")
                       _ (is
                          (= 1 (count rows))
                          "this pass's attempt is readable beside the count")])))
       (testing "every delivery carried the Standard Webhooks headers"
         (is (every? #(get-in % [:headers "webhook-signature"]) @seen)))))))

(deftest claim-sends-once-test
  (let [seen (atom [])]
    (with-test-system
     [sys
      [config-file
       #(assoc-in % [:system/defs :receiver :handler] (receiver seen))]]
     (let [config (runner-config sys)
           base-url (server/http-local-url
                     (system/instance sys [:receiver :jetty-adapter]))
           bank-id "bnk.claim"
           {:keys [delivery-id]}
           (seed config base-url "/ok" {:bank-id bank-id})]
       (testing "two passes over one due delivery send it once (AC-12)"
         (SUT/drain-once config)
         (SUT/drain-once config)
         (nom-test> [delivery (delivery-of config bank-id delivery-id)
                     rows (attempts-of config delivery-id)
                     _ (is (= 1 (count rows)))
                     _ (is (= 1 (:attempts delivery)))]))))))

(deftest reclaims-a-stranded-claim-test
  (let [seen (atom [])]
    (with-test-system
     [sys
      [config-file
       #(assoc-in % [:system/defs :receiver :handler] (receiver seen))]]
     (let [config (runner-config sys)
           base-url (server/http-local-url
                     (system/instance sys [:receiver :jetty-adapter]))
           now (utility/now)]
       (testing "an in-flight claim whose lease has passed is sent once"
         (let [bank-id "bnk.claim.stranded"
               {:keys [delivery-id]} (seed config
                                           base-url
                                           "/ok"
                                           {:bank-id bank-id
                                            :status
                                            :webhook-delivery-status-in-flight
                                            :claim-lease-expires-at (- now 1)})]
           (SUT/drain-once config)
           (SUT/drain-once config)
           (nom-test> [delivery (delivery-of config bank-id delivery-id)
                       rows (attempts-of config delivery-id)
                       _ (is (= :webhook-delivery-status-delivered
                                (:status delivery)))
                       _ (is (= 1 (count rows))
                             "reclaimed once, not once per pass")])))
       (testing "one whose lease still holds is left to the runner holding it"
         (let [bank-id "bnk.claim.held"
               {:keys [delivery-id]}
               (seed config
                     base-url
                     "/ok"
                     {:bank-id bank-id
                      :status :webhook-delivery-status-in-flight
                      :claim-lease-expires-at (+ now 60000)})]
           (SUT/drain-once config)
           (nom-test> [delivery (delivery-of config bank-id delivery-id)
                       rows (attempts-of config delivery-id)
                       _ (is (= :webhook-delivery-status-in-flight
                                (:status delivery)))
                       _ (is (= [] rows))])))))))

(deftest bounded-call-test
  (let [seen (atom [])]
    (with-test-system
     [sys
      [config-file
       #(assoc-in % [:system/defs :receiver :handler] (receiver seen))]]
     (let [config (runner-config sys)
           base-url (server/http-local-url
                     (system/instance sys [:receiver :jetty-adapter]))]
       (testing "a receiver that never answers is abandoned at the timeout"
         (let [bank-id "bnk.bound.slow"
               {:keys [delivery-id]}
               (seed config base-url "/slow" {:bank-id bank-id})
               started (utility/now)
               _ (SUT/drain-once config)
               elapsed (- (utility/now) started)]
           (is (< elapsed (* 2 request-timeout-ms))
               "the drain slot is given up at the timeout, not held")
           (nom-test> [delivery (delivery-of config bank-id delivery-id)
                       rows (attempts-of config delivery-id)
                       _ (is (= :webhook-delivery-status-pending
                                (:status delivery)))
                       _ (is (some? (:last-error delivery)))
                       _ (is (zero? (:response-status (first rows)))
                             "no response arrived, so the row carries none")
                       _ (is (some? (:error (first rows))))])))
       (testing "a 302 to a private address is not followed"
         (let [bank-id "bnk.bound.redirect"
               {:keys [delivery-id]}
               (seed config base-url "/redirect" {:bank-id bank-id})]
           (SUT/drain-once config)
           (nom-test> [delivery (delivery-of config bank-id delivery-id)
                       rows (attempts-of config delivery-id)
                       _ (is (= :webhook-delivery-status-pending
                                (:status delivery)))
                       _ (is
                          (= 302 (:last-response-status delivery))
                          "the redirect is the outcome, not what it pointed at")
                       _ (is (= [302] (mapv :response-status rows)))])))
       (testing "an oversized body does not exhaust the reader"
         (let [bank-id "bnk.bound.big"
               {:keys [delivery-id]}
               (seed config base-url "/big" {:bank-id bank-id})
               stream (ByteArrayInputStream. (.getBytes oversized-body
                                                        StandardCharsets/UTF_8))
               read (#'SUT/read-bounded stream)]
           (is (= domain/max-response-bytes read)
               "the reader stops at the bound rather than at the body's end")
           (SUT/drain-once config)
           (nom-test> [delivery (delivery-of config bank-id delivery-id)
                       rows (attempts-of config delivery-id)
                       _ (is (= :webhook-delivery-status-delivered
                                (:status delivery)))
                       _ (is (= [200] (mapv :response-status rows)))])))
       (testing "an address the rule refuses at send time is never called"
         (let [bank-id "bnk.bound.refused"
               refusing (assoc config :address-check SUT/address-refusal)
               {:keys [delivery-id]}
               (seed config base-url "/ok" {:bank-id bank-id})
               before (count @seen)]
           (SUT/drain-once refusing)
           (is (= before (count @seen)) "no request reached the receiver")
           (nom-test> [delivery (delivery-of config bank-id delivery-id)
                       rows (attempts-of config delivery-id)
                       _ (is (= :webhook-delivery-status-pending
                                (:status delivery)))
                       _ (is (some? (:last-error delivery)))
                       _ (is
                          (some? (:error (first rows)))
                          "the refusal is recorded as this attempt's outcome")])))))))

(deftest address-refusal-test
  (testing "the send-time rule refuses what registration would have"
    (is (some? (SUT/address-refusal "http://93.184.216.34/hooks" nil nil))
        "a plaintext address")
    (is (some? (SUT/address-refusal "https://127.0.0.1/hooks" nil nil))
        "an address that resolves into loopback")
    (is (some? (SUT/address-refusal "https://tenant.example/hooks"
                                    #{"tenant.example"}
                                    nil))
        "one of the platform's own hosts")
    (is (nil? (SUT/address-refusal "http://127.0.0.1/hooks"
                                   nil
                                   {:allowed-schemes ["http" "https"]
                                    :blocked-ranges []}))
        "a local receiver, under the rule a local monolith relaxes")))

(defn- breaker-of
  [config bank-id endpoint-id]
  (circuit-breaker/breaker config
                           (str "webhook-endpoint:" bank-id ":" endpoint-id)))

(deftest breaker-holds-an-endpoint-test
  (let [seen (atom [])
        down (atom true)]
    (with-test-system
     [sys
      [config-file
       #(assoc-in % [:system/defs :receiver :handler] (receiver seen down))]]
     (let [config (-> (runner-config sys)
                      (assoc-in [:delivery-policy :default :initial-backoff-ms]
                                1)
                      (assoc-in [:delivery-policy :breaker]
                                {:failure-threshold 1
                                 :cool-down-ms 300
                                 :max-cool-down-ms 600
                                 :probe-lease-ms 60000}))
           base-url (server/http-local-url
                     (system/instance sys [:receiver :jetty-adapter]))
           bank-id "bnk.breaker"
           {:keys [endpoint-id delivery-id]}
           (seed config base-url "/flaky" {:bank-id bank-id})]
       (testing "a failing endpoint opens its breaker"
         (SUT/drain-once config)
         (nom-test> [breaker (breaker-of config bank-id endpoint-id)
                     _ (is (= "open" (:state breaker)))]))
       (testing "an open breaker claims nothing, the delivery left due"
         (Thread/sleep 10)
         (SUT/drain-once config)
         (nom-test> [delivery (delivery-of config bank-id delivery-id)
                     rows (attempts-of config delivery-id)
                     endpoint (store/find-endpoint config bank-id endpoint-id)
                     _ (is (= :webhook-delivery-status-pending
                              (:status delivery)))
                     _ (is (= 1 (:attempts delivery)))
                     _ (is (= 1 (count rows)))
                     _ (is (= :webhook-endpoint-status-enabled
                              (:status endpoint))
                           "the endpoint is never paused")]))
       (testing "once the cool-down ends, the probe delivers and closes it"
         (reset! down false)
         (Thread/sleep 400)
         (SUT/drain-once config)
         (nom-test> [delivery (delivery-of config bank-id delivery-id)
                     breaker (breaker-of config bank-id endpoint-id)
                     _ (is (= :webhook-delivery-status-delivered
                              (:status delivery)))
                     _ (is (= 2 (:attempts delivery)))
                     _ (is (= "closed" (:state breaker)))]))))))
