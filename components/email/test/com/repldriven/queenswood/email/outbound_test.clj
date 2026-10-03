(ns com.repldriven.queenswood.email.outbound-test
  "The runner's pass against the mail server's breaker: an open breaker
  claims nothing and leaves the delivery due with no attempt counted, and
  a closed one claims it. The delivery is for an invitation nobody wrote,
  so the pass supersedes it without sending."
  (:require
    [com.repldriven.queenswood.email.test-system]

    [com.repldriven.queenswood.email.outbound :as SUT]

    [com.repldriven.queenswood.email.domain :as domain]
    [com.repldriven.queenswood.email.store :as store]

    [com.repldriven.queenswood.circuit-breaker.interface :as circuit-breaker]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(def ^:private config-file "classpath:email/application-test.yml")

(def ^:private delivery-policy
  {:default {:initial-backoff-ms 100
             :backoff-growth 2
             :max-backoff-ms 500
             :max-attempts 3
             :max-age-ms 3600000}
   :breaker {:failure-threshold 1
             :cool-down-ms 120000
             :max-cool-down-ms 240000
             :probe-lease-ms 30000}})

(defn- runner-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])
   :runner-id "runner-a"
   :delivery-policy delivery-policy
   :batch-size 16
   :claim-lease-ms 60000})

(deftest breaker-holds-deliveries-test
  (with-test-system
   [sys config-file]
   (let [config (runner-config sys)
         breaker (:breaker delivery-policy)
         now (utility/now)
         delivery (domain/new-invitation-delivery {:bank-id "bnk.email"
                                                   :invitation-id
                                                   (utility/generate-id "inv")
                                                   :expires-at 1790000000000}
                                                  (str (utility/uuidv7))
                                                  now)
         {:keys [bank-id delivery-id]} delivery]
     (nom-test> [_ (store/save-delivery config delivery)
                 opened
                 (circuit-breaker/record config breaker "smtp" :failed now)
                 _ (testing "a failed send opens the mail server's breaker"
                     (is (= "open" (:state opened))))
                 _ (SUT/drain-once config)
                 held (store/find-delivery config bank-id delivery-id)
                 _ (testing "an open breaker claims nothing and counts nothing"
                     (is (= :email-delivery-status-pending (:status held)))
                     (is (nil? (:claimed-by held)))
                     (is (not (pos? (or (:attempts held) 0)))))
                 _ (circuit-breaker/record config breaker "smtp" :answered now)
                 _ (SUT/drain-once config)
                 drained (store/find-delivery config bank-id delivery-id)
                 _ (testing "a closed breaker lets the delivery be claimed"
                     (is (= :email-delivery-status-superseded
                            (:status drained))))]))))
