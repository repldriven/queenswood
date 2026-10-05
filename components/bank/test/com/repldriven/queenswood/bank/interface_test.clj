(ns ^:eftest/synchronized com.repldriven.queenswood.bank.interface-test
  "What the API scenario suite can't see: that the owner membership, the
  bank-created access event and the owner invitation commit atomically
  with the bank, that a failure after the last write rolls every earlier
  write back, that the service-account client is created only once the
  bank has committed and a create sent again issues one that failed, and
  that the changelog separates a status change from a tier change. Creating a bank over the bus, its providers, and changing
  its tier and status are onboarding/banks/*.edn in test-api-scenarios."
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.bank.interface :as SUT]

    [com.repldriven.queenswood.bank-query.interface :as bank-query]
    [com.repldriven.queenswood.cash-account-product-query.interface :as
     products]
    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]
    [com.repldriven.queenswood.idv-provider.interface :as idv-provider]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.membership.interface :as memberships]
    [com.repldriven.queenswood.membership-query.interface :as q]
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
    [clojure.test :refer [deftest is testing]]))

(defn- fdb-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(def ^:private idv-provider
  "A provider declaration establishing every verification and screening
  the platform policy requires."
  {:verifies ["identity" "liveness" "claimed-identity" "address"]
   :screens ["sanctions" "pep"]})

(def ^:private providers
  "The providers a dispatched command reads, offering one IDV provider
  declaring `idv-provider`."
  {:idv (idv-provider/providers {:default "verifier"
                                 :providers {:verifier {:declaration
                                                        idv-provider}}})})

(def ^:private operator
  {:kind :actor-kind-operator :principal-id "queenswood-admin"})

(defn- create-bank
  [config idp bank-name opts]
  (SUT/new-bank config
                bank-name
                :bank-status-test
                "micro"
                ["GBP"]
                (assoc opts :identity-provider idp :idv-provider idv-provider)))

(defn- owner-invitation
  [email]
  {:email email})

;; `with-redefs` alters a root binding, so a stub here is visible to
;; every namespace beside this one — the API scenarios provision banks
;; through this same seam. Each stub consults a thread-local, so another
;; thread gets the real function.
(def ^:private ^:dynamic *created-bank-id* nil)

(def ^:private ^:dynamic *fail-bank-created?* false)

(def ^:private real-record-bank-created memberships/record-bank-created)

(defn- probed-record-bank-created
  [txn-or-config bank-id opts]
  (if-let [created *created-bank-id*]
    (do (reset! created bank-id)
        (if *fail-bank-created?*
          (error/fail :test/injected {:message "Injected after every write"})
          (real-record-bank-created txn-or-config bank-id opts)))
    (real-record-bank-created txn-or-config bank-id opts)))

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
                                                            "owner@example.com"}
                                         :actor operator})
                  decoded (avro/deserialize-same create-schema bytes)
                  _ (is (= {:email "owner@example.com"}
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
                   {:keys [access-events]} (q/list-access-events config bank-id)
                   _ (is (= [:access-event-kind-bank-created]
                            (mapv :kind access-events)))
                   _ (is (= {:kind :actor-kind-member :principal-id user-id}
                            (:actor (first access-events))))
                   _ (is (= (:membership-id membership)
                            (:membership-id (first access-events))))
                   invitations (q/list-invitations-by-bank config bank-id)
                   _ (is (empty? invitations))
                   listed (q/list-by-user config user-id)
                   _ (is (= 1 (count listed)))]))
     (testing
       "a second bank for the same user commits a second owner membership"
       (nom-test> [{:keys [bank membership]} (create-bank config
                                                          idp
                                                          "Acme Again"
                                                          {:membership
                                                           membership})
                   _ (is (= (:bank-id bank) (:bank-id membership)))
                   listed (q/list-by-user config user-id)
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
                   invitations (q/list-invitations-by-bank config bank-id)
                   _ (is (= [owner-invitation-id]
                            (mapv :invitation-id invitations)))
                   _ (is (= :invitation-status-pending
                            (:status (first invitations))))
                   _ (is (= :role-owner (:role (first invitations))))
                   _ (is (= "Owner@Example.com" (:email (first invitations))))
                   _ (is (= operator (:invited-by (first invitations))))
                   {:keys [access-events]} (q/list-access-events config bank-id)
                   _ (testing "newest first, so the bank-created event is older"
                       (is (= [:access-event-kind-invitation-created
                               :access-event-kind-bank-created]
                              (mapv :kind access-events))))
                   _ (is (every? #(= operator (:actor %)) access-events))]))
     (testing "the owner invitation's creation is on the invitations changelog"
       (let [seen (atom [])]
         (nom-test> [_ (fdb/process-changelog
                        (:record-db config)
                        "bank-owner-invitation-read-back"
                        "invitations"
                        (fn [_ctx bytes]
                          (swap! seen conj (schema/pb->ChangelogEvent bytes)))
                        {:deduplicate? false
                         :keyspace-prefix
                         (system/instance sys [:fdb :keyspace-prefix])})
                     _ (is (= ["invitation-created"] (mapv :event-name @seen)))])))
     (testing
       "a create with neither actor nor membership records an unknown operator"
       (nom-test> [{:keys [bank owner-invitation-id]}
                   (create-bank config idp "Actorless Bank" {})
                   _ (is (nil? owner-invitation-id))
                   {:keys [access-events]}
                   (q/list-access-events config (:bank-id bank))
                   _ (is (= [{:kind :actor-kind-operator
                              :principal-id "unknown"}]
                            (mapv :actor access-events)))
                   invitations (q/list-invitations-by-bank config
                                                           (:bank-id bank))
                   _ (is (empty? invitations))])))))

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
                                  :idv-provider idv-provider
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
                     {:keys [jobs]} (scheduler/list-jobs config bank-id)
                     _ (is (empty? jobs))
                     listed (q/list-by-user config user-id)
                     _ (is (empty? listed))
                     {:keys [access-events]} (q/list-access-events config
                                                                   bank-id)
                     _ (is (empty? access-events))]))))))

(defn- failing-first-create
  "An identity-provider whose first `create-service-account` fails, as
  an unreachable one would, and whose later ones go to `idp`. Each
  create asked for is conj'd onto `asked` and each one made onto
  `created`."
  [idp asked created]
  (reify
   identity-provider/IdentityProvider
     (-create-service-account [_ data]
       (swap! asked conj (:bank-id data))
       (if (= 1 (count @asked))
         (error/fail :test/identity-provider-down
                     {:message "The identity provider is unreachable"})
         (let [result (identity-provider/create-service-account idp data)]
           (swap! created conj (:bank-id data))
           result)))))

(deftest client-created-after-the-commit-test
  (with-test-system
   [sys "classpath:bank/application-test.yml"]
   (let [config (fdb-config sys)
         asked (atom [])
         created (atom [])
         idp (failing-first-create (identity-provider/local-provider {})
                                   asked
                                   created)
         opts {:idempotency-key "ik-client-after-commit" :actor operator}]
     (testing "a client that fails leaves the committed bank without one"
       (let [r (create-bank config idp "Client After Commit Bank" opts)]
         (is (= :test/identity-provider-down (error/kind r)))
         (is (= 1 (count @asked)))
         (is (empty? @created))
         (nom-test> [bank (bank-query/get-bank config (first @asked))
                     _ (is (= "Client After Commit Bank" (:name bank)))])))
     (testing "a create sent again replays the bank and issues its client"
       (nom-test> [{:keys [bank]}
                   (create-bank config idp "Client After Commit Bank" opts)
                   _ (is (= (first @asked) (:bank-id bank)))
                   _ (is (= [(:bank-id bank)] @created))])))))

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
                                      :idv-provider idv-provider
                                      :audience "queenswood-api-test"})
        bank-id (:bank-id bank)
        _ (SUT/change-status config
                             bank-id
                             :bank-status-live
                             {:identity-provider idp
                              :idv-provider idv-provider
                              :audience "queenswood-api-live"})
        _ (SUT/change-tier config
                           bank-id
                           "test-scenario"
                           {:idv-providers (:idv providers)})
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
