(ns com.repldriven.queenswood.schema.interface-test
  "A cash account reads back its retired addresses and its creator.

  The access records read back what was written, and a member
  written before a member could end reads as active. An inbound
  payment reads back its creditor account and transaction only when it
  carries them. A product version reads back its opening reward as a
  plain map, or without one, and a reward its transaction only once
  paid.

  A `provider-payment-rejected` written with the current schema is read by a
  consumer still on the schema at `stable-20260916112610`, which is the
  order a deploy puts them in.

  A required field holding zero reaches the Java parse, and one left out
  is still refused by it.

  Every data category a proto gives a field is a key in the pinned copy
  of Fideslang's taxonomy, and not one it deprecates, and a record reads
  back without the fields of the categories asked for, at any depth."
  (:require
    [com.repldriven.queenswood.schema.interface :as SUT]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.json.interface :as json]

    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]])
  (:import
    (com.google.protobuf Descriptors$Descriptor
                         Descriptors$FieldDescriptor
                         Descriptors$FileDescriptor
                         InvalidProtocolBufferException)
    (com.repldriven.queenswood.schema SchemaProto)
    (com.repldriven.queenswood.schema.fideslang DataCategoryProto)))

(def ^:private payment-address
  {:scheme :payment-address-scheme-scan
   :scan {:sort-code "040004" :account-number "12345678"}})

(def ^:private opened-account
  "An account as `cash-account/domain` builds it at open: a BBAN, and
  no retired addresses."
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
         :retired-payment-addresses
         [{:address payment-address :retired-at 1700000000500}]))

(def ^:private opened-balance
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :account-id "acc.01kprbmgcj35ptc8npmybhh4s8"
   :balance-type :balance-type-default
   :balance-status :balance-status-posted
   :product-type :account-product-type-sub-ledger-current
   :credit 0
   :debit 0
   :created-at 1700000000000})

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
             (into {} (:scan (:address retired)))))))
  (testing "a field an account was never given reads back absent"
    (let [account (SUT/pb->CashAccount (SUT/CashAccount->pb opened-account))]
      (is (not (contains? account :refused-reason)))
      (is (not (contains? account :updated-by)))
      (is (= {:kind :actor-kind-operator :principal-id "test"}
             (:created-by account))))))

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
   :created-at 1700000000000})

(deftest inbound-payment-record-round-trip-test
  (testing "a record with no creditor account or transaction reads neither"
    (let [payment (SUT/pb->InboundPayment (SUT/InboundPayment->pb
                                           suspended-inbound))]
      (is (= :inbound-payment-status-suspended (:status payment)))
      (is (not (contains? payment :creditor-account-id)))
      (is (not (contains? payment :transaction-id)))
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
                       :updated-at 1700000000500
                       :updated-by member-actor
                       :removed-reason "Left the company"
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
  (testing "an accepted invitation keeps who created and who last changed it"
    (let [accepted (assoc pending-invitation
                          :status :invitation-status-accepted
                          :created-by operator-actor
                          :reason "Support ticket 42"
                          :updated-at 1700000000200
                          :updated-by {:kind :actor-kind-member
                                       :principal-id
                                       "usr.01kprbmgcj35ptc8npmybhh4t3"})]
      (is (= accepted (SUT/pb->Invitation (SUT/Invitation->pb accepted))))))
  (testing "a withdrawn invitation keeps who withdrew it and why"
    (let [withdrawn (assoc pending-invitation
                           :status :invitation-status-withdrawn
                           :updated-at 1700000000300
                           :updated-by operator-actor
                           :withdrawn-reason "Sent to the wrong address")]
      (is (= withdrawn (SUT/pb->Invitation (SUT/Invitation->pb withdrawn)))))))

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
  (testing "a sent delivery carries its attempts and message id"
    (let [delivery {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
                    :delivery-id "eml.01kprbmgcj35ptc8npmybhh4t7"
                    :kind :email-kind-invitation
                    :status :email-delivery-status-sent
                    :kind-id "inv.01kprbmgcj35ptc8npmybhh4t2"
                    :message-id "<abc@queenswood.local>"
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
   :balance-products [{:balance-type :balance-type-default
                       :balance-status :balance-status-posted}]
   :allowed-payment-address-schemes [:payment-address-scheme-scan]
   :iso-cash-account-type :iso-cash-account-type-cacc
   :effective-from 20089
   :closure-terms {:interest {:kind {:pay {}}}}
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
    (let [interest
          {:accrual {:banding :interest-banding-marginal
                     :day-count :interest-day-count-actual-365
                     :bands [0 500000]
                     :schedule {:relative {:unit :interest-period-unit-month
                                           :periods
                                           [{:after 0 :rates-bps [500 0]}
                                            {:after 12 :rates-bps [150 150]}]}}}
           :payment
           {:business-day-convention :business-day-convention-modified-following
            :kind {:calendar-day {:every 3 :day-of-month 31 :in-month 3}}
            :calendar :business-day-calendar-gblo}}
          read (SUT/pb->CashAccountProduct
                (SUT/CashAccountProduct->pb
                 (assoc draft-version :interest-terms interest)))]
      (is (= interest (:interest-terms read)))))
  (testing "interest paid when the account closes keeps its empty variant"
    (let [interest {:accrual {:banding :interest-banding-marginal
                              :day-count :interest-day-count-actual-365
                              :bands [0]
                              :schedule {:fixed {:periods [{:from-on 20393
                                                            :rates-bps
                                                            [450]}]}}}
                    :payment {:business-day-convention
                              :business-day-convention-modified-following
                              :kind {:closure-day {}}
                              :calendar :business-day-calendar-none}}
          read (SUT/pb->CashAccountProduct
                (SUT/CashAccountProduct->pb
                 (assoc draft-version :interest-terms interest)))]
      (is (= interest (:interest-terms read)))))
  (testing "a version's closure terms read back as plain maps"
    (is (= {:interest {:kind {:pay {}}}}
           (:closure-terms (SUT/pb->CashAccountProduct
                            (SUT/CashAccountProduct->pb draft-version)))))
    (let [closure
          {:interest {:kind {:pay {}}}
           :early
           [{:unit :interest-period-unit-month :count 3 :kind {:refused {}}}
            {:unit :interest-period-unit-month
             :count 12
             :kind {:charged {:interest {:kind {:forfeit-days {:days 90}}}
                              :fee {:kind {:balance {:rate-bps 100
                                                     :max 5000}}}}}}
            {:unit :interest-period-unit-month
             :count 24
             :kind {:charged {:interest {:kind {:forfeit {}}}
                              :fee {:kind {:flat {:amount 2500}}}}}}]}
          read (SUT/pb->CashAccountProduct
                (SUT/CashAccountProduct->pb
                 (assoc draft-version :closure-terms closure)))]
      (is (= closure (:closure-terms read))))))

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
   :created-at 1700000000000})

(deftest account-reward-record-round-trip-test
  (testing "a deferred reward carries no transaction or update"
    (is (= deferred-reward
           (SUT/pb->AccountReward (SUT/AccountReward->pb deferred-reward))))
    (is (some? (SUT/AccountReward->java deferred-reward))))
  (testing "a paid reward carries the transaction that paid it"
    (let [paid (-> deferred-reward
                   (dissoc :deferred-reason)
                   (assoc :status :account-reward-status-paid
                          :transaction-id "txn.01kprbmgcj35ptc8npmybhh4tb"
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
  (testing "a failed record keeps its kind, code and reason"
    (let [failed (assoc failed-outbound
                        :failed-kind :outbound-payment-failed-kind-refused
                        :failed-reason-code "NARR"
                        :failed-reason "HTTP 400"
                        :updated-at 1700000000100)]
      (is (= failed
             (SUT/pb->OutboundPayment (SUT/OutboundPayment->pb failed))))))
  (testing "a record with no outcome reads none, nor an update"
    (is (= failed-outbound
           (SUT/pb->OutboundPayment (SUT/OutboundPayment->pb
                                     failed-outbound))))))

(def ^:private transaction-rejected-schema
  (avro/json->schema
   (slurp
    (io/resource
     "schemas/payment-provider/payment/provider-payment-rejected.avsc.json"))))

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

(defn- files
  [^Descriptors$FileDescriptor file]
  (into #{file} (mapcat files) (.getDependencies file)))

(defn- messages
  [^Descriptors$Descriptor message]
  (cons message (mapcat messages (.getNestedTypes message))))

(defn- used-categories
  "Each data category a field of a record, or of a message a record's
  file imports, is given, with the field it is on."
  []
  (for [^Descriptors$FileDescriptor file (files (SchemaProto/getDescriptor))
        message (mapcat messages (.getMessageTypes file))
        ^Descriptors$FieldDescriptor field (.getFields ^Descriptors$Descriptor
                                                       message)
        :let [options (.getOptions field)]
        :when (.hasExtension options DataCategoryProto/dataCategory)]
    [(.getFullName field)
     (.getExtension options DataCategoryProto/dataCategory)]))

(deftest data-categories-are-fideslang-keys-test
  (let [taxonomy (json/read-str (slurp (io/resource
                                        "fideslang/data-categories.json"))
                                :key-fn
                                keyword)
        current (into #{}
                      (comp (remove :version_deprecated) (map :fides_key))
                      (:data_category taxonomy))
        used (used-categories)]
    (is (seq used))
    (doseq [[field fides-key] used]
      (is (contains? current fides-key)
          (str field
               " names "
               fides-key
               ", which Fideslang "
               (:tag taxonomy)
               " does not offer")))))

(deftest without-data-categories-test
  (let [check {:bank-id "bnk.a"
               :check-id "chk.a"
               :request {:creditor-name "Ada Lovelace"
                         :account {:sort-code "040004"
                                   :account-number "12345678"}}
               :result {:match-result :match-result-close-match
                        :actual-name "Ada King"}}]
    (testing "a field nested in a message is left out"
      (is (= {:bank-id "bnk.a"
              :check-id "chk.a"
              :request {:account {}}
              :result {:match-result :match-result-close-match}}
             (SUT/without-data-categories :payee-check check ["user"]))))
    (testing "a prefix takes in the keys below it, and nothing else"
      (is (= check
             (SUT/without-data-categories :payee-check check ["system"]))))
    (is (nil? (SUT/without-data-categories :payee-check nil ["user"])))))
