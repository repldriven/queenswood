(ns ^:eftest/synchronized com.repldriven.queenswood.webhook.interface-test
  "The endpoint lifecycle against a real record store: the policy
  refusals evaluated against the seeded policies (AC-21), the
  unique-key read-back that makes a retried registration answer with
  the endpoint it already created, and the transitions end to end.

  The pure rules are asserted in `domain-test`; the record types and
  their indexes in `store-test`."
  (:require
    [com.repldriven.queenswood.webhook.test-system]

    [com.repldriven.queenswood.webhook.interface :as SUT]

    [com.repldriven.queenswood.policy.interface :as policy]

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

(def ^:private micro-endpoint-limit
  "The count the micro tier seeds. AC-21 asserts against the seeded
  value rather than a literal of its own, so moving the seed moves
  this test with it."
  2)

(def ^:private no-policies
  "A bank with no policy at all — the shape AC-21's first half needs,
  where the capability is absent rather than denied."
  [])

(def ^:private allow-manage
  "Capability without limits, for the tests that are not about the cap."
  [{:enabled true
    :capabilities [{:effect :effect-allow :kind {:webhook-endpoint {}}}]}])

(def ^:private tenant-address
  "A public address, written as a literal rather than a name. `register`
  resolves the host for real, and a name would put these tests behind
  DNS; a literal resolves to itself without leaving the machine. The
  address rule's own coverage is in `domain-test`, where the resolved
  addresses are passed in."
  "https://93.184.216.34/hooks")

(defn- endpoint-data
  ([] (endpoint-data nil))
  ([idempotency-key]
   (cond-> {:address tenant-address
            :description "Tenant hooks"
            :kinds ["cash-account.opened"]}
           idempotency-key
           (assoc :idempotency-key idempotency-key))))

(defn- register
  [config bank-id data policies]
  (SUT/register config bank-id data {:policies policies}))

(deftest registration-mints-an-endpoint-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.register"]
     (nom-test> [registered (register config
                                      bank-id
                                      (endpoint-data "ik-register-1")
                                      allow-manage)
                 _ (testing "it comes back enabled, with a secret and an id"
                     (is (string? (:endpoint-id registered)))
                     (is (= :webhook-endpoint-status-enabled
                            (:status registered)))
                     (is (re-find #"^whsec_" (:secret registered)))
                     (is (= "ik-register-1" (:idempotency-key registered))))
                 loaded
                 (SUT/get-endpoint config bank-id (:endpoint-id registered))
                 _ (testing "and the store holds it"
                     (is (= (:endpoint-id registered) (:endpoint-id loaded)))
                     (is (= ["cash-account.opened"] (:kinds loaded))))]))))

(deftest a-retried-registration-reads-the-original-back-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.register.retry"
         key "ik-register-retry"]
     (nom-test> [first-endpoint
                 (register config bank-id (endpoint-data key) allow-manage)
                 retried (register
                          config
                          bank-id
                          (assoc (endpoint-data key) :description "Sent again")
                          allow-manage)
                 _ (testing "the retry answers with the endpoint it created"
                     (is (= (:endpoint-id first-endpoint)
                            (:endpoint-id retried)))
                     (is (= (:secret first-endpoint) (:secret retried)))
                     (is (= "Tenant hooks" (:description retried))))
                 listed (SUT/get-endpoints config bank-id)
                 _ (testing "and leaves one endpoint behind, not two"
                     (is (= 1 (count (:endpoints listed)))))]))))

(deftest a-missing-endpoint-is-not-found-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         result (SUT/get-endpoint config "bnk.missing" "whe.absent")]
     (is (= :webhook-endpoint/not-found (error/kind result))))))

(deftest the-lifecycle-runs-end-to-end-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.lifecycle"]
     (nom-test> [registered (register config
                                      bank-id
                                      (endpoint-data "ik-lifecycle")
                                      allow-manage)
                 id (:endpoint-id registered)
                 disabled
                 (SUT/disable config bank-id id {:policies allow-manage})
                 _ (is (= :webhook-endpoint-status-disabled (:status disabled)))
                 enabled (SUT/enable config bank-id id {:policies allow-manage})
                 _ (is (= :webhook-endpoint-status-enabled (:status enabled)))
                 paused (SUT/pause config bank-id id)
                 _ (is (= :webhook-endpoint-status-paused (:status paused)))
                 resumed (SUT/enable config bank-id id {:policies allow-manage})
                 _ (is (= :webhook-endpoint-status-enabled (:status resumed)))
                 updated (SUT/update-endpoint config
                                              bank-id
                                              id
                                              {:address
                                               "https://93.184.216.35/v2"
                                               :description "Moved"
                                               :kinds ["cash-account.opened"]}
                                              {:policies allow-manage})
                 _ (testing "the update is an absolute set"
                     (is (= "https://93.184.216.35/v2" (:address updated)))
                     (is (= "Moved" (:description updated))))
                 rotated (SUT/rotate-secret config
                                            bank-id
                                            id
                                            {:idempotency-key "ik-rotate"}
                                            {:policies allow-manage})
                 _ (testing "rotation keeps the previous secret and its expiry"
                     (is (not= (:secret registered) (:secret rotated)))
                     (is (= (:secret registered) (:previous-secret rotated)))
                     (is (pos? (:previous-secret-expires-at rotated))))
                 replayed (SUT/rotate-secret config
                                             bank-id
                                             id
                                             {:idempotency-key "ik-rotate"}
                                             {:policies allow-manage})
                 _ (testing "and a retry under that key mints no third secret"
                     (is (= (:secret rotated) (:secret replayed))))
                 removed (SUT/remove-endpoint config
                                              bank-id
                                              id
                                              {:policies allow-manage})
                 _ (is (= :webhook-endpoint-status-removed (:status removed)))
                 _
                 (let [refused
                       (SUT/enable config bank-id id {:policies allow-manage})]
                   (testing "a removed endpoint takes no further transition"
                     (is (= :webhook-endpoint/invalid-status
                            (error/kind refused)))))]))))

(deftest a-bank-without-the-capability-is-refused-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.no.capability"
         refused
         (register config bank-id (endpoint-data "ik-none") no-policies)]
     (testing "the refusal is the policy's, before any write"
       (is (= :policy/denied (error/kind refused))))
     (nom-test> [listed (SUT/get-endpoints config bank-id)
                 _ (testing "and the store holds nothing afterwards"
                     (is (empty? (:endpoints listed))))]))))

(deftest a-bank-at-its-count-limit-is-refused-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.at.the.cap"]
     (nom-test>
       ;; Binding the tier rather than passing :policies puts the policy
       ;; read inside register's own transaction, where production has
       ;; it, and asserts against the seeded count rather than a literal.
       [micro (policy/get-policies-by-tier config "micro")
        _ (is (= 1 (count micro)))
        _ (policy/new-binding config
                              {:policy-id (:policy-id (first micro))
                               :target {:kind {:bank {:bank-id bank-id}}}})
        _ (doseq [n (range micro-endpoint-limit)]
            (is (not (error/anomaly? (SUT/register config
                                                   bank-id
                                                   (endpoint-data (str "ik-cap-"
                                                                       n)))))))
        listed (SUT/get-endpoints config bank-id)
        _ (testing "the bank sits at the seeded cap"
            (is (= micro-endpoint-limit (count (:endpoints listed)))))
        _ (let [refused
                (SUT/register config bank-id (endpoint-data "ik-cap-over"))]
            (testing "the next registration is refused before any write"
              (is (= :policy/limit-exceeded (error/kind refused)))))
        after (SUT/get-endpoints config bank-id)
        _ (testing "and the store still holds only what it admitted"
            (is (= micro-endpoint-limit (count (:endpoints after)))))]))))
