(ns ^:eftest/synchronized com.repldriven.queenswood.bank.interface-test
  "Unknown-command dispatch stays pure; the FDB-backed cases cover
  what the API scenario suite can't see — that the owner membership,
  the bank-created access event and the owner invitation commit
  atomically with the bank, that a command delivered twice creates one
  bank and one client, that a failure after the last write rolls every
  earlier write back, that a tier change rebinds the underlying
  `PolicyBinding` records rather than just stamping `:tier`, and that a
  bank resolves from the sort code it was allocated. Happy-path admin
  creation over the bus is covered by banks/*.edn in
  bank-test-api-scenarios."
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.bank.commands :as commands]
    [com.repldriven.queenswood.bank.interface :as SUT]

    [com.repldriven.queenswood.bank-query.interface :as bank-query]
    [com.repldriven.queenswood.cash-account-product-query.interface :as
     products]
    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.membership.interface :as memberships]
    [com.repldriven.queenswood.party-query.interface :as party-query]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.scheduler.interface :as scheduler]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.identity-provider.interface :as identity-provider]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.java.io :as io]
    [clojure.set :as set]
    [clojure.test :refer [deftest is testing]]))

(defn- fdb-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(def ^:private operator
  {:kind :actor-kind-operator :principal-id "queenswood-admin"})

(defn- create-bank
  [config idp bank-name opts]
  (SUT/new-bank config
                bank-name
                :bank-status-test
                "micro"
                ["GBP"]
                (assoc opts :identity-provider idp)))

(defn- owner-invitation
  [email]
  {:email email :token-hash (:token-hash (memberships/new-invitation-token))})

;; `with-redefs` alters a root binding, so a stub here is visible to
;; every namespace beside this one — the API scenarios provision banks
;; through this same seam. Each stub consults a thread-local, so another
;; thread gets the real function.
(def ^:private ^:dynamic *created-bank-id* nil)

(def ^:private ^:dynamic *fail-bank-created?* false)

(def ^:private ^:dynamic *clients-created* nil)

(def ^:private real-record-bank-created memberships/record-bank-created)

(def ^:private real-create-service-account
  identity-provider/create-service-account)

(defn- probed-record-bank-created
  [txn-or-config bank-id opts]
  (if-let [created *created-bank-id*]
    (do (reset! created bank-id)
        (if *fail-bank-created?*
          (error/fail :test/injected {:message "Injected after every write"})
          (real-record-bank-created txn-or-config bank-id opts)))
    (real-record-bank-created txn-or-config bank-id opts)))

(defn- counted-create-service-account
  [idp opts]
  (when-let [created *clients-created*]
    (swap! created inc))
  (real-create-service-account idp opts))

(deftest unknown-command-test
  (testing "dispatch rejects command names not in the handler registry"
    (let [result (#'commands/dispatch
                  {:schemas {}}
                  {:command "unknown-bank-command" :payload nil})]
      (is (error/rejection? result))
      (is (= :bank/unknown-command (error/kind result))))))

(deftest create-bank-schema-test
  (let [create-schema (avro/json->schema
                       (slurp (io/resource
                               "schemas/banks/create-bank.avsc.json")))
        bank-schema (avro/json->schema
                     (slurp (io/resource "schemas/banks/bank.avsc.json")))]
    (testing "a create-bank map without the new keys encodes and decodes"
      (nom-test> [bytes (avro/serialize create-schema
                                        {:name "Old Shape Bank"
                                         :status :bank-status-test
                                         :tier "micro"
                                         :currencies ["GBP"]})
                  decoded (avro/deserialize-same create-schema bytes)
                  _ (is (= "Old Shape Bank" (:name decoded)))
                  _ (is (nil? (:owner-invitation decoded)))
                  _ (is (nil? (:actor decoded)))]))
    (testing "the owner invitation and actor decode as the brick reads them"
      (nom-test> [bytes (avro/serialize create-schema
                                        {:name "New Shape Bank"
                                         :status :bank-status-test
                                         :tier "micro"
                                         :currencies ["GBP"]
                                         :owner-invitation {:email
                                                            "owner@example.com"
                                                            :token-hash "ab12"}
                                         :actor operator})
                  decoded (avro/deserialize-same create-schema bytes)
                  _ (is (= {:email "owner@example.com" :token-hash "ab12"}
                           (:owner-invitation decoded)))
                  _ (is (= operator (:actor decoded)))]))
    (testing "a bank reply without an owner invitation id encodes and decodes"
      (nom-test> [bytes (avro/serialize bank-schema
                                        {:bank-id "bnk.schema"
                                         :name "Reply Bank"
                                         :status :bank-status-test
                                         :created-at 1
                                         :updated-at 1
                                         :sort-code "000001"})
                  decoded (avro/deserialize-same bank-schema bytes)
                  _ (is (= "bnk.schema" (:bank-id decoded)))
                  _ (is (nil? (:owner-invitation-id decoded)))]))))

(deftest new-bank-with-membership-test
  (with-test-system
   [sys "classpath:bank/application-test.yml"]
   (let [config (fdb-config sys)
         idp (identity-provider/local-provider {})
         user-id "usr.test-onboard"
         membership {:user-id user-id :role :role-owner}]
     (testing
       "creates the bank, owner membership and bank-created event in one
        transaction, the person as actor"
       (nom-test> [{:keys [bank membership owner-invitation-id]}
                   (create-bank config idp "Acme Bank" {:membership membership})
                   bank-id (:bank-id bank)
                   _ (is (re-find #"^bnk\." bank-id))
                   _ (is (= user-id (:user-id membership)))
                   _ (is (= bank-id (:bank-id membership)))
                   _ (is (= :role-owner (:role membership)))
                   _ (is (nil? owner-invitation-id))
                   {:keys [access-events]}
                   (memberships/list-access-events config bank-id)
                   _ (is (= [:access-event-kind-bank-created]
                            (mapv :kind access-events)))
                   _ (is (= {:kind :actor-kind-member :principal-id user-id}
                            (:actor (first access-events))))
                   _ (is (= (:membership-id membership)
                            (:membership-id (first access-events))))
                   invitations (memberships/list-invitations-by-bank config
                                                                     bank-id)
                   _ (is (empty? invitations))
                   listed (memberships/list-by-user config user-id)
                   _ (is (= 1 (count listed)))]))
     (testing
       "a second bank for the same user commits a second owner membership"
       (nom-test> [{:keys [bank membership]} (create-bank config
                                                          idp
                                                          "Acme Again"
                                                          {:membership
                                                           membership})
                   _ (is (= (:bank-id bank) (:bank-id membership)))
                   listed (memberships/list-by-user config user-id)
                   _ (is (= 2 (count listed)))
                   _ (is (= 2 (count (set (map :bank-id listed)))))
                   _ (is (every? #(= :role-owner (:role %)) listed))])))))

(deftest new-bank-with-owner-invitation-test
  (with-test-system
   [sys "classpath:bank/application-test.yml"]
   (let [config (fdb-config sys)
         idp (identity-provider/local-provider {})
         invitation (owner-invitation "Owner@Example.com")]
     (testing
       "writes one bank-created event and one pending owner invitation, both
        in the operator's name"
       (nom-test> [{:keys [bank membership owner-invitation-id]}
                   (create-bank config
                                idp
                                "Invited Bank"
                                {:owner-invitation invitation :actor operator})
                   bank-id (:bank-id bank)
                   _ (is (nil? membership))
                   _ (is (re-find #"^inv\." owner-invitation-id))
                   invitations (memberships/list-invitations-by-bank config
                                                                     bank-id)
                   _ (is (= [owner-invitation-id]
                            (mapv :invitation-id invitations)))
                   _ (is (= :invitation-status-pending
                            (:status (first invitations))))
                   _ (is (= :role-owner (:role (first invitations))))
                   _ (is (= "Owner@Example.com" (:email (first invitations))))
                   _ (is (= operator (:invited-by (first invitations))))
                   {:keys [access-events]}
                   (memberships/list-access-events config bank-id)
                   _ (testing "newest first, so the bank-created event is older"
                       (is (= [:access-event-kind-invitation-created
                               :access-event-kind-bank-created]
                              (mapv :kind access-events))))
                   _ (is (every? #(= operator (:actor %)) access-events))]))
     (testing
       "an owner invitation reusing a taken token hash aborts the create, and
        no bank, event or membership remains"
       (let [created (atom nil)
             user-id "usr.taken-hash"
             r (with-redefs [memberships/record-bank-created
                             probed-record-bank-created]
                 (binding [*created-bank-id* created]
                   (create-bank config
                                idp
                                "Taken Hash Bank"
                                {:owner-invitation
                                 (assoc invitation :email "other@example.com")
                                 :membership {:user-id user-id
                                              :role :role-owner}
                                 :actor operator})))
             bank-id @created]
         (is (error/anomaly? r))
         (is (some? bank-id) "the create reached the owner invitation")
         (is (= :bank/not-found
                (error/kind (bank-query/get-bank config bank-id))))
         (nom-test> [{:keys [access-events]}
                     (memberships/list-access-events config bank-id)
                     _ (is (empty? access-events))
                     invitations (memberships/list-invitations-by-bank config
                                                                       bank-id)
                     _ (is (empty? invitations))
                     listed (memberships/list-by-user config user-id)
                     _ (is (empty? listed))])))
     (testing
       "a create with neither actor nor membership records an unknown operator"
       (nom-test> [{:keys [bank owner-invitation-id]}
                   (create-bank config idp "Actorless Bank" {})
                   _ (is (nil? owner-invitation-id))
                   {:keys [access-events]}
                   (memberships/list-access-events config (:bank-id bank))
                   _ (is (= [{:kind :actor-kind-operator
                              :principal-id "unknown"}]
                            (mapv :actor access-events)))
                   invitations
                   (memberships/list-invitations-by-bank config (:bank-id bank))
                   _ (is (empty? invitations))])))))

(deftest create-bank-delivered-twice-test
  (with-test-system
   [sys "classpath:bank/application-test.yml"]
   (let [schema-for (fn [path] (avro/json->schema (slurp (io/resource path))))
         schemas {"create-bank" (schema-for
                                 "schemas/banks/create-bank.avsc.json")
                  "bank" (schema-for "schemas/banks/bank.avsc.json")}
         idp (identity-provider/local-provider {})
         config (assoc (fdb-config sys) :schemas schemas :identity-provider idp)
         user-id "usr.delivered-twice"
         message (fn [id data]
                   {:command "create-bank"
                    :id id
                    :payload (avro/serialize (schemas "create-bank") data)})
         data {:name "Twice Bank"
               :status :bank-status-test
               :tier "micro"
               :currencies ["GBP"]
               :membership {:user-id user-id :role :role-owner}
               :actor {:kind :actor-kind-member :principal-id user-id}}
         clients (atom 0)
         deliver (fn [msg]
                   (with-redefs [identity-provider/create-service-account
                                 counted-create-service-account]
                     (binding [*clients-created* clients]
                       (#'commands/dispatch config msg))))]
     (testing "the same command id delivered twice writes one bank and client"
       (let [first-reply (deliver (message "ik-bank-twice-0001" data))
             second-reply (deliver (message "ik-bank-twice-0001" data))]
         (is (= "ACCEPTED" (:status first-reply)))
         (is (error/rejection? second-reply))
         (is (= :bank/already-exists (error/kind second-reply)))
         (is (= 1 @clients))
         (nom-test> [banks (bank-query/get-banks config)
                     _ (is (= 1
                              (count (filter #(= "Twice Bank" (:name %))
                                             banks))))
                     listed (memberships/list-by-user config user-id)
                     _ (is (= 1 (count listed)))])))
     (testing "another command id from the same person creates another bank"
       (let [reply (deliver (message "ik-bank-twice-0002" data))]
         (is (= "ACCEPTED" (:status reply)))
         (is (= 2 @clients))))
     (testing
       "the command's actor and owner invitation reach new-bank, and the reply
        carries the invitation id"
       (let [reply (deliver (message "ik-bank-twice-0003"
                                     (-> data
                                         (dissoc :membership)
                                         (assoc :name "Twice Invited Bank"
                                                :actor operator
                                                :owner-invitation
                                                (owner-invitation
                                                 "twice@example.com")))))]
         (is (= "ACCEPTED" (:status reply)))
         (nom-test> [{:keys [bank-id owner-invitation-id]}
                     (avro/deserialize-same (schemas "bank") (:payload reply))
                     invitations (memberships/list-invitations-by-bank config
                                                                       bank-id)
                     _ (is (= [owner-invitation-id]
                              (mapv :invitation-id invitations)))
                     _ (is (= operator (:invited-by (first invitations))))]))))))

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

(deftest new-bank-rolls-back-on-failure-test
  (with-test-system
   [sys "classpath:bank/application-test.yml"]
   (let [config (fdb-config sys)
         idp (identity-provider/local-provider {})
         user-id "usr.rollback"
         created (atom nil)]
     (testing "a failure after the last write leaves nothing behind"
       ;; The bank-created event is `new-bank`'s final write when no owner
       ;; invitation is given, so failing there leaves every other write —
       ;; the seeded jobs and the owner membership included — behind the
       ;; rollback. `fdb/transact` rolls its transaction back when the body
       ;; returns an anomaly; this is the evidence.
       (let [r (with-redefs [memberships/record-bank-created
                             probed-record-bank-created]
                 (binding [*created-bank-id* created
                           *fail-bank-created?* true]
                   (SUT/new-bank config
                                 "Rollback Bank"
                                 :bank-status-test
                                 "micro"
                                 ["GBP"]
                                 {:identity-provider idp
                                  :membership {:user-id user-id
                                               :role :role-owner}})))
             bank-id @created]
         (is (error/anomaly? r))
         (is (= :test/injected (error/kind r)))
         (is (some? bank-id)
             "the injection ran, so every earlier write did too")
         (let [bank (bank-query/get-bank config bank-id)]
           (is (error/rejection? bank))
           (is (= :bank/not-found (error/kind bank))))
         (nom-test> [{:keys [parties]} (party-query/get-parties config bank-id)
                     _ (is (empty? parties))
                     ledger (ledger-accounts/list-accounts config bank-id)
                     _ (is (empty? ledger))
                     {:keys [items]} (products/get-products config bank-id)
                     _ (is (empty? items))
                     ;; The public listing hides internal products, and the
                     ;; own-funds house product is the only one `new-bank`
                     ;; writes — so the raw versions are what actually
                     ;; bite.
                     versions (products/get-versions config bank-id)
                     _ (is (empty? versions))
                     {:keys [accounts]} (cash-accounts/get-accounts config
                                                                    bank-id)
                     _ (is (empty? accounts))
                     bindings (policy/get-bindings-for-bank config bank-id)
                     _ (is (empty? bindings))
                     jobs (scheduler/list-jobs config bank-id)
                     _ (is (empty? jobs))
                     listed (memberships/list-by-user config user-id)
                     _ (is (empty? listed))
                     {:keys [access-events]}
                     (memberships/list-access-events config bank-id)
                     _ (is (empty? access-events))]))))))

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

(deftest changelog-separates-status-from-tier-test
  (with-test-system
   [sys "classpath:bank/application-test.yml"]
   (let [config (fdb-config sys)
         idp (identity-provider/local-provider {})
         seen (atom [])]
     (nom-test>
       [{:keys [bank]} (SUT/new-bank config
                                     "Changelog Bank"
                                     :bank-status-test
                                     "micro"
                                     ["GBP"]
                                     {:identity-provider idp
                                      :audience "queenswood-api-test"})
        bank-id (:bank-id bank)
        _ (SUT/change-status config
                             bank-id
                             :bank-status-live
                             {:identity-provider idp
                              :audience "queenswood-api-live"})
        _ (SUT/change-tier config bank-id "test-scenario")
        ;; `:deduplicate? false` matters: the default keeps only
        ;; the latest entry per record id, which would collapse
        ;; both writes on this one bank into one.
        _ (fdb/process-changelog
           (:record-db config)
           "bank-changelog-read-back"
           "banks"
           (fn [_ctx bytes] (swap! seen conj (schema/pb->ChangelogEvent bytes)))
           {:deduplicate? false
            :keyspace-prefix (system/instance sys [:fdb :keyspace-prefix])})
        _
        (testing
          "a status change and a tier change on one bank are two
           entries, under their own event names and dedup keys"
          (is (= 2 (count @seen)))
          (is (= ["bank-status-changed" "bank-tier-changed"]
                 (mapv :event-name @seen)))
          (is (= 2 (count (set (map :dedup-key @seen))))
              "the tier key does not collide with the status key"))]))))

(deftest get-bank-by-sort-code-test
  (with-test-system
   [sys "classpath:bank/application-test.yml"]
   (let [config (fdb-config sys)
         idp (identity-provider/local-provider {})]
     (nom-test> [{:keys [bank]} (SUT/new-bank config
                                              "Sort Code Bank"
                                              :bank-status-test
                                              "micro"
                                              ["GBP"]
                                              {:identity-provider idp})
                 found (bank-query/get-bank-by-sort-code config
                                                         (:sort-code bank))
                 _ (testing "the allocated sort code resolves back to its bank"
                     (is (some? (:sort-code bank)))
                     (is (= (:bank-id bank) (:bank-id found))))
                 _ (testing "an unallocated sort code resolves to nil"
                     ;; nil, not a `:bank/not-found` rejection: the
                     ;; `bank-query` interface documents it that way and
                     ;; the unmatched-inbound suspense path branches on the
                     ;; nil rather than on a kind.
                     (is (nil? (bank-query/get-bank-by-sort-code config
                                                                 "999999"))))]))))
