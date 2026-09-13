(ns com.repldriven.queenswood.schema.interface-test
  "The cash-account record and the reply schema registered as
  `cash-account` describe one account between them, so what the record
  carries the reply has to be able to carry.

  The reply schema is hand-maintained — nothing generates it from the
  proto — which is what these round-trips are for.

  The access records read back what was written, and a membership
  written before a membership could end reads as active."
  (:require
    [com.repldriven.queenswood.schema.interface :as SUT]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error]

    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]))

(def ^:private payment-address
  {:scheme :payment-address-scheme-scan
   :scan {:sort-code "040004" :account-number "12345678"}})

(def ^:private opened-account
  "An account as `cash-account/domain` builds it at open: a BBAN, and
  no rotation history key at all."
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :account-id "acc.01kprbmgcj35ptc8npmybhh4s8"
   :party-id "pty.01kprbmgcj35ptc8npmybhh4s9"
   :product-id "prd.01kprbmgcj35ptc8npmybhh4se"
   :version-id "prv.01kprbmgcj35ptc8npmybhh4sf"
   :name "Arthur Phillip Dent - Current Account"
   :currency "GBP"
   :account-status :cash-account-status-opened
   :account-type :account-type-personal
   :product-type :product-type-sub-ledger-current
   :payment-addresses [payment-address]
   :bban "04000412345678"
   :created-at 1700000000000
   :updated-at 1700000000000
   :idempotency-key "01kprbmgcj35ptc8npmybhh4sg"})

(def ^:private rotated-account
  (assoc opened-account
         :retired-payment-addresses [{:address payment-address
                                      :retired-at 1700000000500}]
         :last-rotation-idempotency-key "01kprbmgcj35ptc8npmybhh4sh"))

(def ^:private reply-schema
  (avro/json->schema (slurp (io/resource
                             "schemas/cash-accounts/account.avsc.json"))))

(deftest cash-account-record-round-trip-test
  (testing "an account that has been rotated keeps its history"
    (let [account (SUT/pb->CashAccount (SUT/CashAccount->pb rotated-account))
          [retired] (:retired-payment-addresses account)]
      (is (= "04000412345678" (:bban account)))
      (is (= 1700000000500 (:retired-at retired)))
      (is (= :payment-address-scheme-scan (:scheme (:address retired))))
      (is (= {:sort-code "040004" :account-number "12345678"}
             (into {} (:scan (:address retired)))))
      (is (= "01kprbmgcj35ptc8npmybhh4sh"
             (:last-rotation-idempotency-key account)))))
  (testing "the record carries no GL control pointer"
    (let [account (SUT/pb->CashAccount (SUT/CashAccount->pb opened-account))]
      (is (not (contains? account :gl-control-account-id)))
      (is (not (contains? account :last-rotation-idempotency-key))
          "an account that has never been rotated leaves the key unset"))))

(deftest cash-account-reply-schema-test
  (is (not (error/anomaly? reply-schema)) "the reply schema parses")
  (testing "an opened account round-trips"
    ;; The empty vector is what `cash-account/commands` supplies: the
    ;; array field has no null branch to fall back on.
    (let [account (assoc opened-account :retired-payment-addresses [])
          body (avro/deserialize-same reply-schema
                                      (avro/serialize reply-schema account))]
      (is (= "04000412345678" (:bban body)))
      (is (= [] (:retired-payment-addresses body)))
      (testing "and publishes none of the GL fields the record dropped"
        (is (empty? (select-keys body
                                 [:gl-code :gl-account-type :gl-account-class
                                  :required :gl-control-code]))))))
  (testing "a rotated account's history round-trips"
    (let [body (avro/deserialize-same reply-schema
                                      (avro/serialize reply-schema
                                                      rotated-account))]
      (is (= [{:address payment-address :retired-at 1700000000500}]
             (:retired-payment-addresses body))))))

(def ^:private owner
  {:membership-id "mem.01kprbmgcj35ptc8npmybhh4t0"
   :user-id "usr.01kprbmgcj35ptc8npmybhh4t1"
   :bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :role :role-owner
   :created-at 1700000000000
   :updated-at 1700000000000})

(def ^:private member-actor
  {:kind :actor-kind-member :principal-id "usr.01kprbmgcj35ptc8npmybhh4t1"})

(deftest membership-record-round-trip-test
  (testing "a membership written without a status reads back active"
    (is (= (assoc owner :status :membership-status-active)
           (SUT/pb->Membership (SUT/Membership->pb owner)))))
  (testing "an ended membership keeps who ended it and when"
    (let [ended (assoc owner
                       :role :role-viewer
                       :status :membership-status-ended
                       :ended-at 1700000000500
                       :ended-by member-actor
                       :invitation-id "inv.01kprbmgcj35ptc8npmybhh4t2")]
      (is (= ended (SUT/pb->Membership (SUT/Membership->pb ended))))
      (is (= (SUT/membership-status->pb-enum :membership-status-ended)
             (.getStatus (SUT/Membership->java ended)))))))

(def ^:private pending-invitation
  {:invitation-id "inv.01kprbmgcj35ptc8npmybhh4t2"
   :bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :email "Ford.Prefect@example.com"
   :email-lower "ford.prefect@example.com"
   :role :role-developer
   :status :invitation-status-pending
   :token-hash (apply str (repeat 64 "a"))
   :expires-at 1700604800000
   :invited-by member-actor
   :created-at 1700000000000
   :updated-at 1700000000000})

(deftest invitation-record-round-trip-test
  (testing "a pending invitation carries no reason and no accepting user"
    (is (= pending-invitation
           (SUT/pb->Invitation (SUT/Invitation->pb pending-invitation))))
    (is (some? (SUT/Invitation->java pending-invitation))))
  (testing "an operator's accepted invitation keeps both"
    (let [accepted
          (assoc pending-invitation
                 :status :invitation-status-accepted
                 :invited-by {:kind :actor-kind-operator :principal-id "ops"}
                 :reason "Support ticket 42"
                 :accepted-by-user-id "usr.01kprbmgcj35ptc8npmybhh4t3")]
      (is (= accepted (SUT/pb->Invitation (SUT/Invitation->pb accepted)))))))

(deftest access-event-record-round-trip-test
  (testing "a role change carries the roles either side"
    (let [event {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
                 :access-event-id "aev.01kprbmgcj35ptc8npmybhh4t4"
                 :kind :access-event-kind-role-changed
                 :actor member-actor
                 :subject-user-id "usr.01kprbmgcj35ptc8npmybhh4t3"
                 :membership-id "mem.01kprbmgcj35ptc8npmybhh4t5"
                 :role-before :role-viewer
                 :role-after :role-admin
                 :occurred-at 1700000000000}]
      (is (= event (SUT/pb->AccessEvent (SUT/AccessEvent->pb event))))
      (is (some? (SUT/AccessEvent->java event)))))
  (testing "a bank's creation carries only what it has"
    (let [event {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
                 :access-event-id "aev.01kprbmgcj35ptc8npmybhh4t6"
                 :kind :access-event-kind-bank-created
                 :actor {:kind :actor-kind-operator :principal-id "ops"}
                 :occurred-at 1700000000000}]
      (is (= event (SUT/pb->AccessEvent (SUT/AccessEvent->pb event)))))))
