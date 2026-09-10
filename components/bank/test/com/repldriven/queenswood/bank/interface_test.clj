(ns ^:eftest/synchronized com.repldriven.queenswood.bank.interface-test
  "Unknown-command dispatch stays pure; the FDB-backed cases cover
  what the API scenario suite can't see — that the owner membership
  commits atomically with the bank and that a duplicate onboarding
  aborts the whole transaction, that a mid-flow failure leaves no
  half-built tenant behind, and that a tier change rebinds the
  underlying `PolicyBinding` records rather than just stamping
  `:tier`. Happy-path admin creation over the bus is covered by
  banks/*.edn in bank-test-api-scenarios."
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.bank.commands :as commands]
    [com.repldriven.queenswood.bank.interface :as SUT]

    [com.repldriven.queenswood.bank-query.interface :as bank-query]
    [com.repldriven.queenswood.cash-account-query.interface :as
     cash-accounts-query]
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.membership.interface :as memberships]
    [com.repldriven.queenswood.party-query.interface :as party-query]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.schema.interface :as schema]
    [com.repldriven.queenswood.scheduler.interface :as scheduler]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.identity-provider.interface :as identity-provider]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

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
                                              nil
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
                             nil
                             ["GBP"]
                             {:identity-provider idp
                              :membership {:user-id user-id
                                           :role :role-owner}})]
         (is (error/rejection? r))
         (is (= :membership/already-exists (error/kind r)))
         (nom-test> [listed (memberships/list-by-user config user-id)
                     _ (is (= 1 (count listed)))]))))))

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
                                              nil
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

(defn- provisioned
  "Everything `new-bank` writes for `bank-id`, as one map: the bank
  record, its org party, its ledger chart, its house accounts and its
  policy bindings. Read after a failed create it must be empty
  throughout — that is the all-or-nothing guarantee, and nothing else
  asserts it."
  [config bank-id]
  {:bank (bank-query/get-bank config bank-id)
   :parties (:parties (party-query/get-parties config bank-id))
   :ledger-accounts (ledger-accounts/list-accounts config bank-id)
   :accounts (:accounts (cash-accounts-query/get-accounts config bank-id))
   :bindings (policy/get-bindings-for-bank config bank-id)})

(deftest new-bank-rolls-back-test
  (with-test-system
   [sys "classpath:bank/application-test.yml"]
   (let [config (fdb-config sys)
         idp (identity-provider/local-provider {})
         attempted (atom nil)]
     (testing
       "a failure after the last foundational write leaves nothing behind"
       ;; `seed-jobs` runs after every foundational write, so failing
       ;; there is the latest point at which the whole tenant is in the
       ;; transaction and none of it committed — and the only seam that
       ;; hands the test the id of a bank that will never exist.
       (let [r (with-redefs [scheduler/seed-jobs
                             (fn [_ bank-id]
                               (reset! attempted bank-id)
                               (error/fail :test/injected
                                           {:message "Injected mid-flow failure"
                                            :bank-id bank-id}))]
                 (SUT/new-bank config
                               "Rollback Probe Bank"
                               :bank-status-test
                               "micro"
                               ["GBP"]
                               {:identity-provider idp}))
             bank-id @attempted]
         (is (error/anomaly? r))
         (is (some? bank-id) "the injected failure saw a bank id")
         (let [{:keys [bank parties ledger-accounts accounts bindings]}
               (provisioned config bank-id)]
           (is (error/rejection? bank))
           (is (= :bank/not-found (error/kind bank)))
           (is (empty? parties) "the org party rolled back")
           (is (empty? ledger-accounts) "the ledger chart rolled back")
           (is (empty? accounts) "the own-funds house account rolled back")
           (is (empty? bindings) "the tier bindings rolled back")))))))

(deftest new-bank-guards-test
  (with-test-system
   [sys "classpath:bank/application-test.yml"]
   (let [config (fdb-config sys)
         idp (identity-provider/local-provider {})]
     (testing "a tier matching no policy is rejected before any write"
       (let [r (SUT/new-bank config
                             "Unknown Tier Bank"
                             :bank-status-test
                             "no-such-tier"
                             ["GBP"]
                             {:identity-provider idp})]
         (is (error/rejection? r))
         (is (= :bank/unknown-tier (error/kind r)))))
     (testing "a tierless bank is created, bound to no tier policy"
       (nom-test> [{:keys [bank]} (SUT/new-bank config
                                                "Tierless Bank"
                                                :bank-status-test
                                                nil
                                                ["GBP"]
                                                {:identity-provider idp})
                   _ (is (not (contains? bank :tier)))
                   bindings (policy/get-bindings-for-bank config
                                                          (:bank-id bank))
                   _ (is (empty? bindings))]))
     (testing "a bank with no identity-provider is rejected"
       (let [r (SUT/new-bank config
                             "No IDP Bank"
                             :bank-status-test
                             "micro"
                             ["GBP"]
                             {})]
         (is (error/rejection? r))
         (is (= :bank/missing-identity-provider (error/kind r)))))
     (testing "every currency the API offers reaches a house account"
       (nom-test> [{:keys [bank]} (SUT/new-bank config
                                                "Three Currency Bank"
                                                :bank-status-test
                                                "micro"
                                                ["EUR" "GBP" "USD"]
                                                {:identity-provider idp})
                   {:keys [accounts]}
                   (cash-accounts-query/get-accounts config (:bank-id bank))
                   _ (is (= #{"EUR" "GBP" "USD"}
                            (set (map :currency accounts))))
                   chart (ledger-accounts/list-accounts config (:bank-id bank))
                   _ (is (= {"EUR" 9 "GBP" 9 "USD" 9}
                            (frequencies (map :currency chart)))
                         "one full nine-row chart per currency")]))
     (testing "a currency the own-funds template disallows is rejected"
       (let [r (SUT/new-bank config
                             "Yen Bank"
                             :bank-status-test
                             "micro"
                             ["JPY"]
                             {:identity-provider idp})]
         (is (error/rejection? r))
         (is (= :cash-account-product/currency-not-allowed (error/kind r))))))))

(deftest bank-by-sort-code-test
  (with-test-system
   [sys "classpath:bank/application-test.yml"]
   (let [config (fdb-config sys)
         idp (identity-provider/local-provider {})]
     (testing "a bank is found by the sort code its BBANs carry"
       (nom-test> [{:keys [bank]} (SUT/new-bank config
                                                "Sort Code Lookup Bank"
                                                :bank-status-test
                                                "micro"
                                                ["GBP"]
                                                {:identity-provider idp})
                   found (bank-query/get-bank-by-sort-code config
                                                           (:sort-code bank))
                   _ (is (= (:bank-id bank) (:bank-id found)))]))
     (testing "an unallocated sort code answers nil rather than an anomaly"
       (is (nil? (bank-query/get-bank-by-sort-code config "999999")))))))

(defn- changelog-entries
  "Every changelog entry the banks store holds for `bank-id`, decoded.
  A fresh consumer id starts from no checkpoint, so this reads the log
  from the beginning; `:deduplicate? false` keeps both transitions
  rather than collapsing to the latest per record."
  [sys config bank-id]
  (let [seen (atom [])]
    (fdb/process-changelog (:record-db config)
                           (str "bank-changelog-test-" (utility/uuidv7))
                           "banks"
                           (fn [_ bytes]
                             (swap! seen conj
                               (schema/pb->ChangelogEvent bytes)))
                           {:deduplicate? false
                            :keyspace-prefix (system/instance
                                              sys
                                              [:fdb :keyspace-prefix])})
    (filterv (fn [e] (= bank-id (:causation-id e))) @seen)))

(deftest changelog-entries-test
  (with-test-system
   [sys "classpath:bank/application-test.yml"]
   (let [config (fdb-config sys)
         idp (identity-provider/local-provider {})]
     (nom-test>
       [{:keys [bank]} (SUT/new-bank config
                                     "Changelog Bank"
                                     :bank-status-test
                                     "micro"
                                     ["GBP"]
                                     {:identity-provider idp
                                      :audience "queenswood-api-test"})
        bank-id (:bank-id bank)
        _ (SUT/change-tier config bank-id "test-scenario")
        _ (SUT/change-status config
                             bank-id
                             :bank-status-live
                             {:identity-provider idp
                              :audience "queenswood-api-live"})
        entries (changelog-entries sys config bank-id)
        _
        (testing
          "each transition is written back under its own event
                      name, with dedup keys a tier and a status change
                      cannot share"
          (is (= ["bank-tier-changed" "bank-status-changed"]
                 (mapv :event-name entries)))
          (is (= [(str bank-id ":tier:test-scenario")
                  (str bank-id ":status:bank-status-live")]
                 (mapv :dedup-key entries)))
          (is (every? (fn [e] (pos? (count (:payload e)))) entries)
              "each entry carries its Avro payload"))]))))
