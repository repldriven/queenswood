(ns com.repldriven.queenswood.schema.interface-test
  "The cash-account record and the reply schema registered as
  `cash-account` describe one account between them, so what the record
  carries the reply has to be able to carry.

  The reply schema is hand-maintained — nothing generates it from the
  proto — which is what these round-trips are for.

  The access records read back what was written, and a member
  written before a member could end reads as active. An inbound
  payment reads back its creditor account and transaction only when it
  carries them. A product version reads back its opening reward as a
  plain map, or without one, and a reward its transaction only once
  paid.

  A `transaction-rejected` written with the current schema is read by a
  consumer still on the schema at `stable-20260916112610`, which is the
  order a deploy puts them in.

  A required field holding zero reaches the Java parse, and one left out
  is still refused by it."
  (:require
    [com.repldriven.queenswood.schema.interface :as SUT]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error]

    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]])
  (:import
    (com.google.protobuf InvalidProtocolBufferException)))

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
   :version-from-on 20089
   :created-by {:kind :actor-kind-operator :principal-id "test"}
   :name "Arthur Phillip Dent - Current Account"
   :currency "GBP"
   :status :cash-account-status-opened
   :account-type :account-type-personal
   :product-type :account-product-type-sub-ledger-current
   :payment-addresses [payment-address]
   :bban "04000412345678"
   :created-at 1700000000000
   :updated-at 1700000000000
   :idempotency-key "01kprbmgcj35ptc8npmybhh4sg"})

(def ^:private rotated-account
  (assoc opened-account
         :retired-payment-addresses [{:address payment-address
                                      :retired-at 1700000000500}]
         :rotation {:idempotency-key "01kprbmgcj35ptc8npmybhh4sh"
                    :status :address-rotation-status-completed}))

(def ^:private reply-schema
  (avro/json->schema (slurp (io/resource
                             "schemas/cash-accounts/account.avsc.json"))))

(def ^:private opened-balance
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :account-id "acc.01kprbmgcj35ptc8npmybhh4s8"
   :balance-type :balance-type-default
   :balance-status :balance-status-posted
   :product-type :account-product-type-sub-ledger-current
   :credit 0
   :debit 0
   :created-at 1700000000000
   :updated-at 1700000000000})

(deftest required-field-test
  (testing "a required field holding zero is written"
    (is (= 0 (.getCredit (SUT/AccountBalance->java opened-balance))))
    (is (= opened-balance
           (into {}
                 (SUT/pb->AccountBalance (SUT/AccountBalance->pb
                                          opened-balance))))))
  (testing "a required field left out is refused by the Java parse"
    (is (thrown-with-msg? InvalidProtocolBufferException
                          #"bank_id"
                          (SUT/AccountBalance->java (dissoc opened-balance
                                                     :bank-id))))))

(deftest cash-account-record-round-trip-test
  (testing "an account that has been rotated keeps its history"
    (let [account (SUT/pb->CashAccount (SUT/CashAccount->pb rotated-account))
          [retired] (:retired-payment-addresses account)]
      (is (= "04000412345678" (:bban account)))
      (is (= 1700000000500 (:retired-at retired)))
      (is (= :payment-address-scheme-scan (:scheme (:address retired))))
      (is (= {:sort-code "040004" :account-number "12345678"}
             (into {} (:scan (:address retired)))))
      (is (= {:idempotency-key "01kprbmgcj35ptc8npmybhh4sh"
              :status :address-rotation-status-completed}
             (:rotation account)))))
  (testing "a field an account was never given reads back absent"
    (let [account (SUT/pb->CashAccount (SUT/CashAccount->pb opened-account))]
      (is (not (contains? account :rotation))
          "an account that has never been rotated has no rotation")
      (is (not (contains? account :suspended-at)))
      (is (not (contains? account :suspended-by)))
      (is (= {:kind :actor-kind-operator :principal-id "test"}
             (:created-by account))))))

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
                                 [:gl-code :gl-account-type :account-class
                                  :required :gl-control-code]))))))
  (testing "a rotated account's history round-trips"
    (let [body (avro/deserialize-same reply-schema
                                      (avro/serialize reply-schema
                                                      rotated-account))]
      (is (= [{:address payment-address :retired-at 1700000000500}]
             (:retired-payment-addresses body))))))

(def ^:private suspended-inbound
  "An inbound as `payment/domain` parks it: no creditor account and, until
  a suspense posting, no transaction."
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :payment-id "pmt.01kprbmgcj35ptc8npmybhh4s5"
   :status :inbound-payment-status-suspended
   :scheme-type :scheme-type-fps
   :amount 2500
   :currency "GBP"
   :end-to-end-id "01a0b01c-546b-76ac-9462-53eec8a61307"
   :scheme-transaction-id "01a0b01c-5496-7d64-a052-ad4c57e5d0ef"
   :business-day 20713
   :suspended-at 1700000000000
   :created-at 1700000000000})

(deftest inbound-payment-record-round-trip-test
  (testing "a record with no creditor account or transaction reads neither"
    (let [payment (SUT/pb->InboundPayment (SUT/InboundPayment->pb
                                           suspended-inbound))]
      (is (= :inbound-payment-status-suspended (:status payment)))
      (is (= 1700000000000 (:suspended-at payment)))
      (is (not (contains? payment :creditor-account-id)))
      (is (not (contains? payment :transaction-id)))
      (is (not (contains? payment :settled-at)))
      (is (not (contains? payment :updated-at)))))
  (testing "a settled record keeps both"
    (let [payment (SUT/pb->InboundPayment
                   (SUT/InboundPayment->pb
                    (assoc suspended-inbound
                           :status :inbound-payment-status-settled
                           :creditor-account-id "acc.01kprbmgcj35ptc8npmybhh4s8"
                           :transaction-id "txn.01kprbmgcj35ptc8npmybhh4t2")))]
      (is (= "acc.01kprbmgcj35ptc8npmybhh4s8" (:creditor-account-id payment)))
      (is (= "txn.01kprbmgcj35ptc8npmybhh4t2" (:transaction-id payment))))))

(def ^:private member-actor
  {:kind :actor-kind-member :principal-id "usr.01kprbmgcj35ptc8npmybhh4t1"})

(def ^:private owner
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :member-id "mem.01kprbmgcj35ptc8npmybhh4t0"
   :status :member-status-active
   :role :role-owner
   :user-id "usr.01kprbmgcj35ptc8npmybhh4t1"
   :created-at 1700000000000
   :created-by member-actor
   :updated-at 1700000000000})

(deftest member-record-round-trip-test
  (testing "an active member keeps who created it"
    (is (= owner (SUT/pb->Member (SUT/Member->pb owner)))))
  (testing "a removed member keeps who removed it, when and why"
    (let [ended (assoc owner
                       :role :role-viewer
                       :status :member-status-removed
                       :ended-at 1700000000500
                       :ended-by member-actor
                       :ended-reason "Left the company"
                       :invitation-id "inv.01kprbmgcj35ptc8npmybhh4t2")]
      (is (= ended (SUT/pb->Member (SUT/Member->pb ended))))
      (is (= (SUT/member-status->pb-enum :member-status-removed)
             (.getStatus (SUT/Member->java ended)))))))

(def ^:private pending-invitation
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :invitation-id "inv.01kprbmgcj35ptc8npmybhh4t2"
   :status :invitation-status-pending
   :role :role-developer
   :email "Ford.Prefect@example.com"
   :email-lower "ford.prefect@example.com"
   :token-hash (apply str (repeat 64 "a"))
   :expires-at 1700604800000
   :created-at 1700000000000
   :created-by member-actor
   :updated-at 1700000000000})

(def ^:private operator-actor {:kind :actor-kind-operator :principal-id "ops"})

(deftest invitation-record-round-trip-test
  (testing "a pending invitation carries no reason and no transition"
    (is (= pending-invitation
           (SUT/pb->Invitation (SUT/Invitation->pb pending-invitation))))
    (is (some? (SUT/Invitation->java pending-invitation))))
  (testing "an operator's resent, then accepted, invitation keeps each act"
    (let [accepted (assoc pending-invitation
                          :status :invitation-status-accepted
                          :created-by operator-actor
                          :reason "Support ticket 42"
                          :resent-at 1700000000100
                          :resent-by operator-actor
                          :accepted-at 1700000000200
                          :accepted-by {:kind :actor-kind-member
                                        :principal-id
                                        "usr.01kprbmgcj35ptc8npmybhh4t3"})]
      (is (= accepted (SUT/pb->Invitation (SUT/Invitation->pb accepted))))))
  (testing "a withdrawn invitation keeps who withdrew it and why"
    (let [withdrawn (assoc pending-invitation
                           :status :invitation-status-withdrawn
                           :withdrawn-at 1700000000300
                           :withdrawn-by operator-actor
                           :withdrawn-reason "Sent to the wrong address")]
      (is (= withdrawn (SUT/pb->Invitation (SUT/Invitation->pb withdrawn)))))))

(deftest member-role-change-record-round-trip-test
  (let [change {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
                :member-id "mem.01kprbmgcj35ptc8npmybhh4t5"
                :role-change-id "rch.01kprbmgcj35ptc8npmybhh4t4"
                :role-before :role-viewer
                :role-after :role-admin
                :created-at 1700000000000
                :created-by member-actor}]
    (testing "a role change carries the roles either side and who moved it"
      (is (= change
             (SUT/pb->MemberRoleChange (SUT/MemberRoleChange->pb change))))
      (is (some? (SUT/MemberRoleChange->java change))))
    (testing "and the reason when one was given"
      (let [with-reason (assoc change :reason "Leads the team")]
        (is (= with-reason
               (SUT/pb->MemberRoleChange (SUT/MemberRoleChange->pb
                                          with-reason))))))))

(deftest email-delivery-record-round-trip-test
  (testing "a pending delivery carries no message id, send or failure"
    (let [delivery {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
                    :delivery-id "eml.01kprbmgcj35ptc8npmybhh4t7"
                    :kind :email-kind-invitation
                    :status :email-delivery-status-pending
                    :kind-id "inv.01kprbmgcj35ptc8npmybhh4t2"
                    :idempotency-key "01kprbmgcj35ptc8npmybhh4t8"
                    :created-at 1700000000000
                    :updated-at 1700000000000
                    :attempt-count 0
                    :next-attempt-at 1700000000000}]
      (is (= delivery (SUT/pb->EmailDelivery (SUT/EmailDelivery->pb delivery))))
      (is (some? (SUT/EmailDelivery->java delivery)))))
  (testing "a sent delivery carries its attempts, message id and send"
    (let [delivery {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
                    :delivery-id "eml.01kprbmgcj35ptc8npmybhh4t7"
                    :kind :email-kind-invitation
                    :status :email-delivery-status-sent
                    :kind-id "inv.01kprbmgcj35ptc8npmybhh4t2"
                    :message-id "<abc@queenswood.local>"
                    :sent-at 1700000060000
                    :idempotency-key "01kprbmgcj35ptc8npmybhh4t8"
                    :created-at 1700000000000
                    :updated-at 1700000060000
                    :attempt-count 2
                    :traceparent
                    "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"}]
      (is (= delivery
             (SUT/pb->EmailDelivery (SUT/EmailDelivery->pb delivery))))))
  (testing "a failed delivery carries its reason"
    (let [delivery {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
                    :delivery-id "eml.01kprbmgcj35ptc8npmybhh4t7"
                    :kind :email-kind-invitation
                    :status :email-delivery-status-failed
                    :kind-id "inv.01kprbmgcj35ptc8npmybhh4t2"
                    :idempotency-key "01kprbmgcj35ptc8npmybhh4t8"
                    :created-at 1700000000000
                    :updated-at 1700000060000
                    :attempt-count 11
                    :failure-reason "connection refused"}]
      (is (= delivery
             (SUT/pb->EmailDelivery (SUT/EmailDelivery->pb delivery)))))))

(def ^:private draft-version
  "A version as `cash-account-product/domain` builds it at create, with
  no reward."
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :product-id "prd.01kprbmgcj35ptc8npmybhh4se"
   :version-id "prv.01kprbmgcj35ptc8npmybhh4sf"
   :version-number 1
   :status :version-status-draft
   :product-type :account-product-type-sub-ledger-current
   :template-id "tpl.00000000000000000000000001"
   :balance-sheet-side :balance-sheet-side-liability
   :name "Current Account"
   :currency "GBP"
   :internal false
   :balance-products [{:balance-type :balance-type-default
                       :balance-status :balance-status-posted}]
   :allowed-payment-address-schemes [:payment-address-scheme-scan]
   :iso-cash-account-type :iso-cash-account-type-cacc
   :effective-from 20089
   :created-at 1700000000000
   :created-by {:kind :actor-kind-operator :principal-id "queenswood-admin"}
   :updated-at 1700000000000})

(deftest cash-account-product-record-round-trip-test
  (testing "a version with no reward reads back with none"
    (let [read (SUT/pb->CashAccountProduct (SUT/CashAccountProduct->pb
                                            draft-version))]
      (is (empty? (:reward-terms read)))
      (is (some? (SUT/CashAccountProduct->java draft-version)))))
  (testing "a version's reward terms read back as plain maps"
    (let [rewards [{:kind :reward-kind-opening :amount 1000}]
          version (assoc draft-version :reward-terms rewards)
          read (SUT/pb->CashAccountProduct (SUT/CashAccountProduct->pb
                                            version))]
      (is (= rewards (:reward-terms read)))
      (is (= 1000
             (.. (SUT/CashAccountProduct->java version)
                 (getRewardTerms 0)
                 getAmount)))))
  (testing "a version's interest terms read back as plain maps"
    (let [interest {:basis :interest-schedule-basis-relative
                    :banding :interest-banding-marginal
                    :steps [{:bands [{:up-to 500000 :rate-bps 500}
                                     {:rate-bps 0}]}
                            {:starts-after-months 12 :bands [{:rate-bps 150}]}]
                    :day-count :interest-day-count-actual-actual
                    :payment {:frequency :interest-payment-frequency-monthly
                              :day :interest-payment-day-last-of-month}}
          read (SUT/pb->CashAccountProduct
                (SUT/CashAccountProduct->pb
                 (assoc draft-version :interest-terms interest)))]
      (is (= interest (:interest-terms read))))))

(def ^:private deferred-reward
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :reward-id "rwd.01kprbmgcj35ptc8npmybhh4t9"
   :status :account-reward-status-deferred
   :kind :reward-kind-opening
   :account-id "acc.01kprbmgcj35ptc8npmybhh4s8"
   :product-id "prd.01kprbmgcj35ptc8npmybhh4se"
   :version-id "prv.01kprbmgcj35ptc8npmybhh4sf"
   :amount 1000
   :currency "GBP"
   :deferred-reason "house account cannot cover it"
   :deferred-at 1700000000000
   :created-at 1700000000000})

(deftest account-reward-record-round-trip-test
  (testing "a deferred reward carries no transaction, paid-at or update"
    (is (= deferred-reward
           (SUT/pb->AccountReward (SUT/AccountReward->pb deferred-reward))))
    (is (some? (SUT/AccountReward->java deferred-reward))))
  (testing "a paid reward carries the transaction that paid it"
    (let [paid (-> deferred-reward
                   (dissoc :deferred-reason)
                   (assoc :status :account-reward-status-paid
                          :transaction-id "txn.01kprbmgcj35ptc8npmybhh4tb"
                          :paid-at 1700003600000
                          :updated-at 1700003600000))]
      (is (= paid (SUT/pb->AccountReward (SUT/AccountReward->pb paid))))
      (is (= (SUT/account-reward-status->int :account-reward-status-paid)
             (.getNumber (.getStatus (SUT/AccountReward->java paid))))))))

(def ^:private failed-outbound
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :payment-id "pmt.01kprbmgcj35ptc8npmybhh4s6"
   :status :outbound-payment-status-failed
   :scheme-type :scheme-type-fps
   :debtor-account-id "acc.01kprbmgcj35ptc8npmybhh4s8"
   :creditor-name "Arthur Dent"
   :creditor-bban "04000412345678"
   :amount 2500
   :currency "GBP"
   :transaction-id "txn.01kprbmgcj35ptc8npmybhh4s9"
   :business-day 20713
   :idempotency-key "5b2f0f6e-outbound"
   :created-at 1700000000000
   :created-by member-actor})

(deftest outbound-payment-record-round-trip-test
  (testing "a failed record keeps its kind, code, reason and when"
    (let [failed (assoc failed-outbound
                        :failed-kind :outbound-payment-failed-kind-refused
                        :failed-reason-code "NARR"
                        :failed-reason "HTTP 400"
                        :failed-at 1700000000100
                        :updated-at 1700000000100)]
      (is (= failed
             (SUT/pb->OutboundPayment (SUT/OutboundPayment->pb failed))))))
  (testing "a record with no outcome reads none, nor an update"
    (is (= failed-outbound
           (SUT/pb->OutboundPayment (SUT/OutboundPayment->pb
                                     failed-outbound))))))

(def ^:private transaction-rejected-schema
  (avro/json->schema
   (slurp (io/resource
           "schemas/schemes/payments/transaction-rejected.avsc.json"))))

(def ^:private stable-transaction-rejected-schema
  (avro/json->schema
   (slurp (io/resource
           "schema/transaction-rejected-stable-20260916112610.avsc.json"))))

(def ^:private returned-inbound
  {:end-to-end-id "e2e.01kprbmgcj35ptc8npmybhh4t9"
   :scheme "FasterPayments"
   :debit-credit-code :debit-credit-code-credit
   :cancellation-code "HELD_DECLINED"
   :cancellation-reason "Account closed"
   :is-return true
   :timestamp-rejected 1700000000000})

(deftest transaction-rejected-schema-test
  (is (not (error/anomaly? transaction-rejected-schema)) "the schema parses")
  (let [event (avro/serialize
               transaction-rejected-schema
               (assoc returned-inbound :creditor-bban "04000412345678"))]
    (testing "a reader on the stable schema reads the fields it knows"
      (let [body (avro/deserialize-same stable-transaction-rejected-schema
                                        event)]
        (is (= returned-inbound body))))
    (testing "a reader on the current schema reads the creditor BBAN"
      (let [body (avro/deserialize-same transaction-rejected-schema event)]
        (is (= "04000412345678" (:creditor-bban body)))))))
