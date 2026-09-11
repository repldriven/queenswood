(ns com.repldriven.queenswood.webhook.domain-test
  "Pure-function tests for the webhook domain rules: the address rule
  over already-resolved addresses, each lifecycle guard's source
  state, and the pause rule against its two constants.

  What only a record store can show — the unique idempotency-key
  index, the count index and the policy refusals evaluated against
  seeded policies — lives in `interface-test`."
  (:require
    [com.repldriven.queenswood.webhook.domain :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private permissive-policies
  "Single allow-everything policy. The `:kind` is a oneof map, and an
  empty fields map matches every request because the matcher only
  constrains on set fields."
  [{:enabled true
    :capabilities [{:kind {:webhook-endpoint {}} :effect :effect-allow}]}])

(def ^:private public-address ["93.184.216.34"])

(defn- endpoint
  [status]
  {:bank-id "bnk.1"
   :endpoint-id "whe.1"
   :address "https://tenant.example/hooks"
   :status status
   :secret "whsec_test"
   :idempotency-key "ik-1"
   :created-at 1700000000000
   :updated-at 1700000000000})

;; ---------------------------------------------------------------------------
;; Address validation

(deftest address-must-be-https-test
  (testing "a non-HTTPS scheme is refused whatever it resolves to"
    (doseq [address ["http://tenant.example/hooks"
                     "ftp://tenant.example/hooks"
                     "tenant.example/hooks"]]
      (let [result (SUT/check-address address public-address #{})]
        (is (error/rejection? result) address)
        (is (= :webhook-endpoint/invalid-address (error/kind result)))))))

(deftest address-ranges-are-refused-test
  (testing "each range a tenant may not be reached on"
    (doseq [[address reason] [["127.0.0.1" "loopback"]
                              ["127.10.20.30" "loopback"]
                              ["169.254.1.1" "link-local"]
                              ["169.254.169.254" "link-local"]
                              ["10.0.0.1" "private"]
                              ["172.16.0.1" "private"]
                              ["172.31.255.255" "private"]
                              ["192.168.1.1" "private"]
                              ["100.64.0.1" "carrier-grade NAT"]
                              ["0.0.0.0" "unspecified"]
                              ["::1" "loopback"]
                              ["fe80::1" "link-local"]
                              ["fd00::1" "unique-local"]]]
      (let [result
            (SUT/check-address "https://tenant.example/hooks" [address] #{})]
        (is (error/rejection? result) address)
        (is (= :webhook-endpoint/invalid-address (error/kind result)))
        (is (= reason (:reason (error/payload result))) address)))))

(deftest address-outside-the-ranges-is-allowed-test
  (testing "a public address passes, on either family"
    (is (nil?
         (SUT/check-address "https://tenant.example/hooks" public-address #{})))
    (is (nil? (SUT/check-address "https://tenant.example/hooks"
                                 ["2606:2800:220:1:248:1893:25c8:1946"]
                                 #{}))))
  (testing "and a neighbour of a blocked range is not caught by it"
    (is (nil?
         (SUT/check-address "https://tenant.example/hooks" ["172.32.0.1"] #{})))
    (is (nil?
         (SUT/check-address "https://tenant.example/hooks" ["11.0.0.1"] #{})))))

(deftest address-refuses-the-platforms-own-host-test
  (let [result (SUT/check-address "https://api.queenswood.test/hooks"
                                  public-address
                                  #{"api.queenswood.test"})]
    (is (= :webhook-endpoint/invalid-address (error/kind result)))
    (is (= "host is the platform's own" (:reason (error/payload result))))))

(deftest address-refuses-a-host-that-resolves-to-nothing-test
  (let [result (SUT/check-address "https://tenant.example/hooks" [] #{})]
    (is (= :webhook-endpoint/invalid-address (error/kind result)))
    (is (= "host resolves to no address" (:reason (error/payload result))))))

(deftest address-refuses-what-it-cannot-parse-test
  (testing "an address that is neither family is refused, not allowed"
    (let [result (SUT/check-address "https://tenant.example/hooks"
                                    ["not-an-address"]
                                    #{})]
      (is (= :webhook-endpoint/invalid-address (error/kind result))))))

(deftest one-blocked-address-among-many-refuses-test
  (testing "a host answering with a public and a private address is refused"
    (let [result (SUT/check-address "https://tenant.example/hooks"
                                    ["93.184.216.34" "10.1.2.3"]
                                    #{})]
      (is (= "private" (:reason (error/payload result)))))))

;; ---------------------------------------------------------------------------
;; Lifecycle guards

(deftest transitions-from-their-source-states-test
  (testing "each transition takes the states it accepts"
    (is (= :webhook-endpoint-status-enabled
           (:status (SUT/enable (endpoint :webhook-endpoint-status-disabled)
                                permissive-policies))))
    (is (= :webhook-endpoint-status-enabled
           (:status (SUT/enable (endpoint :webhook-endpoint-status-paused)
                                permissive-policies))))
    (is (= :webhook-endpoint-status-disabled
           (:status (SUT/disable (endpoint :webhook-endpoint-status-enabled)
                                 permissive-policies))))
    (is (= :webhook-endpoint-status-paused
           (:status (SUT/pause (endpoint :webhook-endpoint-status-enabled)))))
    (is (= :webhook-endpoint-status-removed
           (:status (SUT/remove-endpoint (endpoint
                                          :webhook-endpoint-status-enabled)
                                         permissive-policies))))))

(deftest transitions-guard-their-source-state-test
  (testing "each transition refuses the states it does not accept"
    (doseq [[result allowed]
            [[(SUT/enable (endpoint :webhook-endpoint-status-enabled)
                          permissive-policies)
              #{:webhook-endpoint-status-disabled
                :webhook-endpoint-status-paused}]
             [(SUT/disable (endpoint :webhook-endpoint-status-disabled)
                           permissive-policies)
              #{:webhook-endpoint-status-enabled
                :webhook-endpoint-status-paused}]
             [(SUT/pause (endpoint :webhook-endpoint-status-paused))
              #{:webhook-endpoint-status-enabled}]
             [(SUT/remove-endpoint (endpoint :webhook-endpoint-status-removed)
                                   permissive-policies)
              #{:webhook-endpoint-status-enabled
                :webhook-endpoint-status-disabled
                :webhook-endpoint-status-paused}]]]
      (is (= :webhook-endpoint/invalid-status (error/kind result)))
      (is (= allowed (:allowed (error/payload result)))))))

(deftest invalid-status-carries-what-the-recipe-mandates-test
  (let [result (SUT/enable (endpoint :webhook-endpoint-status-enabled)
                           permissive-policies)
        payload (error/payload result)]
    (is (= "whe.1" (:endpoint-id payload)))
    (is (= :webhook-endpoint-status-enabled (:status payload)))
    (is (string? (:message payload)))
    (is (set? (:allowed payload)))))

(deftest a-removed-endpoint-is-not-editable-test
  (testing "update and rotate-secret both refuse a removed endpoint"
    (doseq [result
            [(SUT/update-endpoint (endpoint :webhook-endpoint-status-removed)
                                  {:address "https://tenant.example/new"}
                                  public-address
                                  #{}
                                  permissive-policies)
             (SUT/rotate-secret (endpoint :webhook-endpoint-status-removed)
                                "whsec_next" 1700000100000
                                "ik-rotate" permissive-policies)]]
      (is (= :webhook-endpoint/invalid-status (error/kind result))))))

(deftest the-status-guard-runs-before-the-address-rule-test
  (testing "a removed endpoint is refused on its status, not its address"
    (let [result (SUT/update-endpoint (endpoint
                                       :webhook-endpoint-status-removed)
                                      {:address "http://tenant.example/new"}
                                      ["127.0.0.1"]
                                      #{}
                                      permissive-policies)]
      (is (= :webhook-endpoint/invalid-status (error/kind result))))))

(deftest rotate-secret-keeps-the-previous-pair-test
  (let [rotated (SUT/rotate-secret (endpoint :webhook-endpoint-status-enabled)
                                   "whsec_next" 1700000100000
                                   "ik-rotate" permissive-policies)]
    (is (= "whsec_next" (:secret rotated)))
    (is (= "whsec_test" (:previous-secret rotated)))
    (is (= 1700000100000 (:previous-secret-expires-at rotated)))
    (is (= "ik-rotate" (:rotation-idempotency-key rotated)))))

(deftest not-found-is-a-rejection-test
  (is (= :webhook-endpoint/not-found
         (error/kind (SUT/ensure-found nil "bnk.1" "whe.missing"))))
  (is (= "whe.1"
         (:endpoint-id (SUT/ensure-found (endpoint
                                          :webhook-endpoint-status-enabled)
                                         "bnk.1"
                                         "whe.1")))))

;; ---------------------------------------------------------------------------
;; The pause rule

(deftest the-pause-rule-holds-its-two-bounds-test
  (let [now 1700000000000
        inside (- now (quot SUT/pause-window-ms 2))
        outside (- now (* 2 SUT/pause-window-ms))]
    (testing "outside the window and past the attempt minimum, it pauses"
      (is (true? (SUT/should-pause? outside now SUT/pause-minimum-attempts))))
    (testing "a success inside the window holds it open"
      (is (false? (SUT/should-pause? inside now SUT/pause-minimum-attempts))))
    (testing "too few attempts holds it open, however old the success"
      (is (false?
           (SUT/should-pause? outside now (dec SUT/pause-minimum-attempts))))
      (is (false? (SUT/should-pause? nil now 0)))
      (is (false? (SUT/should-pause? nil now nil))))
    (testing "an endpoint that has never succeeded pauses on the attempts"
      (is (true? (SUT/should-pause? nil now SUT/pause-minimum-attempts))))
    (testing "exactly at the window is not yet outside it"
      (is (false? (SUT/should-pause? (- now SUT/pause-window-ms)
                                     now
                                     SUT/pause-minimum-attempts))))))
