(ns ^:eftest/synchronized com.repldriven.queenswood.webhook.outbound-test
  "The delivery runner against a receiver this test starts and a real
  record store: the outcomes and their attempt rows (AC-11), the claim
  that makes a second pass send nothing (AC-12), the four bounds on the
  call that can be observed from outside it (AC-13), and the pause with
  its changelog entry (AC-14).

  The signing the deliveries carry is asserted in `signing-test`; the
  schedule and the outcome rule in `domain-test`."
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.webhook.outbound :as SUT]

    [com.repldriven.queenswood.schema.interface :as schema]
    [com.repldriven.queenswood.webhook.domain :as domain]
    [com.repldriven.queenswood.webhook.store :as store]

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
  delivery carried is readable from the receiving side."
  [seen]
  (fn [_ctx]
    (fn [{:keys [uri headers]}]
      (swap! seen conj {:uri uri :headers headers})
      (case uri
        "/ok" {:status 200 :body "{}"}
        "/fail" {:status 500 :body "nope"}
        "/slow" (do (Thread/sleep (* 2 domain/request-timeout-ms))
                    {:status 200 :body "{}"})
        "/redirect" {:status 302
                     :headers {"Location" "http://127.0.0.1:1/private"}
                     :body ""}
        "/big" {:status 200 :body oversized-body}
        {:status 404 :body ""}))))

(defn- runner-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])
   :runner-id "runner-test"
   :address-check reachable})

(defn- seed
  "One enabled endpoint pointing at `path` on the receiver, one
  notification, and one delivery due now. Returns the three ids."
  [config base-url path
   {:keys [bank-id attempts last-success-at status claim-lease-expires-at]}]
  (let [suffix (str (utility/uuidv7))
        endpoint-id (str "whe." suffix)
        notification-id (str "whn." suffix)
        delivery-id (str "whd." suffix)
        now (utility/now)]
    (nom-test>
      [_ (store/save-endpoint
          config
          (utility/assoc-some
           {:bank-id bank-id
            :endpoint-id endpoint-id
            :address (str base-url path)
            :status :webhook-endpoint-status-enabled
            :secret "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"
            :idempotency-key (str "ik." suffix)
            :created-at now
            :updated-at now}
           :last-success-at
           last-success-at))
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
               {:keys [delivery-id endpoint-id]}
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
                             "the claim is released with the outcome")
                       endpoint (store/find-endpoint config bank-id endpoint-id)
                       _ (is (pos? (:last-success-at endpoint))
                             "the success the pause window measures from")])))
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
               {:keys [delivery-id]}
               (seed config
                     base-url
                     "/fail"
                     {:bank-id bank-id :attempts (dec domain/max-attempts)})]
           (SUT/drain-once config)
           (nom-test> [delivery (delivery-of config bank-id delivery-id)
                       rows (attempts-of config delivery-id)
                       _ (is (= :webhook-delivery-status-failed
                                (:status delivery)))
                       _ (is (= domain/max-attempts (:attempts delivery)))
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
                      :claim-lease-expires-at (+ now domain/claim-lease-ms)})]
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
           (is (< elapsed (* 2 domain/request-timeout-ms))
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
    (is (some? (SUT/address-refusal "http://93.184.216.34/hooks" nil))
        "a plaintext address")
    (is (some? (SUT/address-refusal "https://127.0.0.1/hooks" nil))
        "an address that resolves into loopback")
    (is (some? (SUT/address-refusal "https://tenant.example/hooks"
                                    #{"tenant.example"}))
        "one of the platform's own hosts")))

(deftest pause-on-repeated-failure-test
  (let [seen (atom [])
        entries (atom [])]
    (with-test-system
     [sys
      [config-file
       #(assoc-in % [:system/defs :receiver :handler] (receiver seen))]]
     (let [config (runner-config sys)
           base-url (server/http-local-url
                     (system/instance sys [:receiver :jetty-adapter]))
           bank-id "bnk.pause"
           {:keys [endpoint-id delivery-id]}
           (seed config
                 base-url
                 "/fail"
                 {:bank-id bank-id
                  :attempts (dec domain/pause-minimum-attempts)
                  :last-success-at (- (utility/now)
                                      (* 2 domain/pause-window-ms))})]
       (testing "an endpoint failing past the window is paused (AC-14)"
         (SUT/drain-once config)
         (nom-test> [delivery (delivery-of config bank-id delivery-id)
                     endpoint (store/find-endpoint config bank-id endpoint-id)
                     _ (is (= domain/pause-minimum-attempts
                              (:attempts delivery)))
                     _ (is (= :webhook-endpoint-status-paused
                              (:status endpoint)))]))
       (testing "an endpoint that succeeded inside the window is not paused"
         (let [fresh-bank "bnk.pause.recent"
               {fresh-endpoint :endpoint-id}
               (seed config
                     base-url
                     "/fail"
                     {:bank-id fresh-bank
                      :attempts (dec domain/pause-minimum-attempts)
                      :last-success-at (- (utility/now)
                                          (quot domain/pause-window-ms 2))})]
           (SUT/drain-once config)
           (nom-test> [endpoint
                       (store/find-endpoint config fresh-bank fresh-endpoint)
                       _ (is (= :webhook-endpoint-status-enabled
                                (:status endpoint)))])))
       (testing "the pause co-committed its changelog entry"
         (nom-test> [_ (fdb/process-changelog
                        (:record-db config)
                        "webhook-pause-read-back"
                        "webhook-endpoints"
                        (fn [_ctx bytes]
                          (swap! entries conj
                            (schema/pb->ChangelogEvent bytes)))
                        {:deduplicate? false
                         :keyspace-prefix
                         (system/instance sys [:fdb :keyspace-prefix])})
                     _ (is (= ["webhook-endpoint-status-changed"]
                              (mapv :event-name @entries)))
                     _ (is (str/starts-with? (:dedup-key (first @entries))
                                             endpoint-id))]))))))
