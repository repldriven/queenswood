(ns ^:eftest/synchronized com.repldriven.queenswood.bank.interface-test
  "Unknown-command dispatch stays pure; the FDB-backed cases cover
  what the API scenario suite can't see — that the owner membership
  commits atomically with the bank and that a duplicate onboarding
  aborts the whole transaction, and that a tier change rebinds the
  underlying `PolicyBinding` records rather than just stamping
  `:tier`. Happy-path admin creation over the bus is covered by
  banks/*.edn in bank-test-api-scenarios."
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.bank.commands :as commands]
    [com.repldriven.queenswood.bank.interface :as SUT]

    [com.repldriven.queenswood.bank-query.interface :as bank-query]
    [com.repldriven.queenswood.membership.interface :as memberships]
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.identity-provider.interface :as identity-provider]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.set :as set]
    [clojure.test :refer [deftest is testing]]))

(defn- fdb-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(deftest unknown-command-test
  (testing "dispatch rejects command names not in the handler registry"
    (let [result (#'commands/dispatch
                  {:schemas {}}
                  {:command "unknown-bank-command" :payload nil})]
      (is (error/rejection? result))
      (is (= :bank/unknown-command (error/kind result))))))

(deftest new-bank-with-membership-test
  (with-test-system
   [sys "classpath:bank/application-test.yml"]
   (let [config (fdb-config sys)
         idp (identity-provider/local-provider {})
         user-id "usr.test-onboard"]
     (testing "creates the bank and owner membership in one transaction"
       (nom-test> [{:keys [bank membership]} (SUT/new-bank
                                              config
                                              "Acme Bank"
                                              :bank-status-test
                                              "micro"
                                              ["GBP"]
                                              {:identity-provider idp
                                               :membership {:user-id user-id
                                                            :role :role-owner}})
                   _ (is (re-find #"^bnk\." (:bank-id bank)))
                   _ (is (= user-id (:user-id membership)))
                   _ (is (= (:bank-id bank) (:bank-id membership)))
                   _ (is (= :role-owner (:role membership)))
                   listed (memberships/list-by-user config user-id)
                   _ (is (= 1 (count listed)))]))
     (testing "a second bank for the same user aborts with no writes"
       (let [r (SUT/new-bank config
                             "Acme Again"
                             :bank-status-test
                             "micro"
                             ["GBP"]
                             {:identity-provider idp
                              :membership {:user-id user-id
                                           :role :role-owner}})]
         (is (error/rejection? r))
         (is (= :membership/already-exists (error/kind r)))
         (nom-test> [listed (memberships/list-by-user config user-id)
                     _ (is (= 1 (count listed)))]))))))

(deftest new-bank-unknown-tier-test
  (with-test-system
   [sys "classpath:bank/application-test.yml"]
   (let [config (fdb-config sys)
         idp (identity-provider/local-provider {})]
     (testing "a tier resolving to no policies is rejected, leaving no bank"
       (let [r (SUT/new-bank config
                             "Unknown Tier Bank"
                             :bank-status-test
                             "no-such-tier"
                             ["GBP"]
                             {:identity-provider idp})]
         (is (error/rejection? r))
         (is (= :bank/unknown-tier (error/kind r)))
         (nom-test> [banks (bank-query/get-banks config)
                     _ (is (not-any? #(= "Unknown Tier Bank" (:name %)) banks))]))))))

(deftest change-tier-test
  (with-test-system
   [sys "classpath:bank/application-test.yml"]
   (let [config (fdb-config sys)
         idp (identity-provider/local-provider {})]
     (nom-test> [{:keys [bank]} (SUT/new-bank config
                                              "Tier Change Bank"
                                              :bank-status-test
                                              "micro"
                                              ["GBP"]
                                              {:identity-provider idp})
                 bank-id (:bank-id bank)
                 micro-policies (policy/get-policies-by-tier config "micro")
                 bindings-before (policy/get-bindings-for-bank config bank-id)
                 _ (testing
                     "the new bank is bound to its creation tier's policies"
                     (is (= (set (map :policy-id micro-policies))
                            (set (map :policy-id bindings-before)))))
                 test-scenario-policies
                 (policy/get-policies-by-tier config "test-scenario")
                 updated (SUT/change-tier config bank-id "test-scenario")
                 bindings-after (policy/get-bindings-for-bank config bank-id)
                 _ (testing
                     "change-tier stamps the new tier and rebinds its policies"
                     (is (= "test-scenario" (:tier updated)))
                     (is (= (set (map :policy-id test-scenario-policies))
                            (set (map :policy-id bindings-after))))
                     (is (empty? (set/intersection
                                  (set (map :policy-id micro-policies))
                                  (set (map :policy-id bindings-after))))))
                 _ (testing
                     "an unknown tier is rejected, leaving bindings untouched"
                     (let [r (SUT/change-tier config bank-id "no-such-tier")]
                       (is (error/rejection? r))
                       (is (= :bank/unknown-tier (error/kind r)))
                       (is (= (set (map :policy-id bindings-after))
                              (set (map :policy-id
                                        (policy/get-bindings-for-bank
                                         config
                                         bank-id)))))))]))))

(deftest change-status-test
  (with-test-system
   [sys "classpath:bank/application-test.yml"]
   (let [config (fdb-config sys)
         idp (identity-provider/local-provider {})]
     (nom-test> [{:keys [bank]} (SUT/new-bank config
                                              "Status Change Bank"
                                              :bank-status-test
                                              "micro"
                                              ["GBP"]
                                              {:identity-provider idp
                                               :audience "queenswood-api-test"})
                 bank-id (:bank-id bank)
                 updated (SUT/change-status config
                                            bank-id
                                            :bank-status-live
                                            {:identity-provider idp
                                             :audience "queenswood-api-live"})
                 _ (testing "flips test to live"
                     (is (= :bank-status-live (:status updated))))
                 _ (testing "rejects flipping to the same status"
                     (let [r (SUT/change-status config
                                                bank-id
                                                :bank-status-live
                                                {:identity-provider idp
                                                 :audience
                                                 "queenswood-api-live"})]
                       (is (error/rejection? r))
                       (is (= :bank/invalid-status (error/kind r)))))]))))
