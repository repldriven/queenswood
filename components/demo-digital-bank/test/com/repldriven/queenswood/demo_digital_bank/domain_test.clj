(ns com.repldriven.queenswood.demo-digital-bank.domain-test
  (:require
    [com.repldriven.queenswood.demo-digital-bank.domain :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(deftest normalise-phone-test
  (testing "every usual spelling of a UK mobile lands on E.164"
    (is (= "+447700900123" (SUT/normalise-phone "07700 900123")))
    (is (= "+447700900123" (SUT/normalise-phone "+44 7700 900123")))
    (is (= "+447700900123" (SUT/normalise-phone "447700900123")))
    (is (= "+447700900123" (SUT/normalise-phone "0044 7700 900123")))
    (is (= "+447700900123" (SUT/normalise-phone "7700-900-123")))))

(deftest sort-code-test
  (is (= "04-00-75" (SUT/sort-code "040075")))
  (is (nil? (SUT/sort-code nil))))

(deftest party-registration-test
  (testing "the screens' details become the platform's person, defaults filled"
    (let [party (SUT/party-registration
                 {:given-name "Amara"
                  :family-name "Okafor"
                  :date-of-birth "1994-03-12"
                  :address
                  {:street "Mare Street" :town "London" :postcode "E8 3RH"}
                  :national-identifier {:value "QQ123456C"}})]
      (is (= "person" (:type party)))
      (is (= "Amara Okafor" (:display-name party)))
      (is (= "GB" (:nationality party)))
      (is (= "GBR" (get-in party [:address :country])))
      (is (=
           {:type "national-insurance" :value "QQ123456C" :issuing-country "GB"}
           (:national-identifier party))))))

(deftest published-products-test
  (let [listing {:items [{:product-id "p1"
                          :versions
                          [{:status "draft" :name "Old" :product-type "current"}
                           {:status "published"
                            :name "Everyday"
                            :product-type "current"
                            :interest-rate-bps 0}]}
                         {:product-id "p2"
                          :versions [{:status "draft"
                                      :name "Fixed"
                                      :product-type "term-deposit"}]}]}]
    (is (= [{:id "p1"
             :kind "cur"
             :name "Everyday"
             :product-type "current"
             :rate-bps 0}]
           (SUT/published-products listing)))))

(deftest account-label-test
  (is (= "Current account" (SUT/account-label "cur" "Everyday" 0)))
  (is (= "Easy-access saver · 4.10% AER"
         (SUT/account-label "sav" "Rainy Day" 410)))
  (is (= "1 Year Fixed · 4.65% AER"
         (SUT/account-label "fix" "1 Year Fixed" 465))))

(defn- leg
  [type side amount day]
  {:leg-id (str type "-" day)
   :transaction-id (str "txn-" day)
   :transaction-type type
   :status "posted"
   :account-id "acc"
   :balance-type "default"
   :balance-status "posted"
   :side side
   :amount amount
   :currency "GBP"
   :created-at (str (java.time.Instant/ofEpochMilli (+ (* day 86400000)
                                                       43200000)))})

(deftest sparkline-test
  (testing "seven closing balances, walked back through posted legs"
    (let [today 100
          legs [(leg "inbound-transfer" "credit" 1000 94)
                (leg "outbound-transfer" "debit" 300 97)
                (leg "outbound-transfer" "debit" 100 100)]]
      (is (= [1000 1000 1000 700 700 700 600] (SUT/sparkline 600 legs today)))))
  (testing "a pending leg has not closed any day yet"
    (is (= [50 50 50 50 50 50 50]
           (SUT/sparkline 50
                          [(assoc (leg "outbound-transfer" "debit" 20 100)
                                  :balance-status
                                  "pending-outgoing")]
                          100)))))

(deftest transactions-test
  (testing "a transfer between own accounts names the other side"
    (let [out (assoc (leg "internal-transfer" "debit" 20000 100)
                     :account-id "cur"
                     :leg-id "out")
          in (assoc (leg "internal-transfer" "credit" 20000 100)
                    :account-id "sav"
                    :leg-id "in")
          txns (SUT/transactions {"cur" [out] "sav" [in]}
                                 {"cur" "Everyday" "sav" "Rainy Day"}
                                 [])]
      (is (= #{"Transfer to Rainy Day" "Transfer from Everyday"}
             (set (map :who txns))))
      (is (= #{"Saved"} (set (map :cat txns))))
      (is (= #{-20000 20000} (set (map :amount txns))))))
  (testing "an accrual on another balance is not a transaction"
    (is (= []
           (SUT/transactions {"sav" [(assoc (leg "interest-accrual" "credit"
                                                 3 100)
                                            :balance-type
                                            "interest-accrued")]}
                             {}
                             []))))
  (testing "a payment in flight is one pending row, to the payee"
    (let [reserved (assoc (leg "outbound-transfer" "debit" 2500 100)
                          :leg-id "reserved"
                          :transaction-id "txn-reserve"
                          :balance-status "pending-outgoing"
                          :reference "Towel")
          payments [{:transaction-id "txn-reserve"
                     :account-id "acc"
                     :amount 2500
                     :reference "Towel"
                     :name "Arthur Dent"
                     :created-at (:created-at reserved)}]
          [sent :as txns] (SUT/transactions {"acc" [reserved]} {} payments)]
      (is (= 1 (count txns)))
      (is (= ["Arthur Dent" "pending" -2500 "Payment"]
             [(:who sent) (:status sent) (:amount sent) (:cat sent)]))
      (testing "and once settled, one posted row to the same payee"
        (let [released (assoc (leg "outbound-transfer" "credit" 2500 101)
                              :leg-id "released"
                              :transaction-id "txn-settle"
                              :balance-status "pending-outgoing"
                              :reference "Towel")
              posted (assoc (leg "outbound-transfer" "debit" 2500 101)
                            :leg-id "posted"
                            :transaction-id "txn-settle"
                            :status "pending"
                            :reference "Towel")
              [settled :as txns] (SUT/transactions {"acc" [reserved released
                                                           posted]}
                                                   {}
                                                   payments)]
          (is (= 1 (count txns)))
          (is (= ["Arthur Dent" "posted" -2500]
                 [(:who settled) (:status settled) (:amount settled)]))))
      (testing "and once failed, nothing"
        (let [reversed (assoc (leg "outbound-transfer" "credit" 2500 101)
                              :leg-id "reversed"
                              :balance-status "pending-outgoing")]
          (is (= []
                 (SUT/transactions {"acc" [reserved reversed]} {} payments)))))
      (testing "and a payment the bank did not make is a Payment"
        (is (= "Payment"
               (:who (first (SUT/transactions {"acc" [reserved]} {} []))))))
      (testing "and a payment with no reference settles under an empty one"
        (let [settled (assoc (leg "outbound-transfer" "debit" 500 101)
                             :transaction-id "txn-settle-2"
                             :reference "")
              [row] (SUT/transactions {"acc" [settled]}
                                      {}
                                      [{:transaction-id "txn-reserve-2"
                                        :account-id "acc"
                                        :amount 500
                                        :reference nil
                                        :name "Arthur Dent"
                                        :created-at (:created-at reserved)}])]
          (is (= ["Arthur Dent" "posted"] [(:who row) (:status row)])))))))

(deftest payee-check-outcome-test
  (is (= {:check-id "chk.1" :outcome "close-match" :name-held "Jane A Doe"}
         (SUT/payee-check-outcome {:check-id "chk.1"
                                   :result {:match-result "close-match"
                                            :actual-name "Jane A Doe"}})))
  (is (= "unavailable"
         (:outcome (SUT/payee-check-outcome
                    {:result {:match-result "match-result-unavailable"}})))))

(deftest outbound-payment-request-test
  (is (= {:debtor-account-id "acc.1"
          :creditor-bban "04006212345678"
          :creditor-name "Arthur Dent"
          :currency "GBP"
          :amount 2500
          :scheme "fps"
          :reference "Towel"}
         (SUT/outbound-payment-request "acc.1" {:name "Arthur Dent"
                                                :sort-code "040062"
                                                :account-number "12345678"}
                                       2500 " Towel ")))
  (testing "a blank reference is left off"
    (is (not (contains? (SUT/internal-payment-request "a" "b" 1 "  ")
                        :reference)))))

(deftest check-deposit-test
  (let [fix {:kind "fix" :name "1 Year Fixed"}
        sav {:kind "sav" :name "Rainy Day"}
        cur {:account-id "acc.cur" :product-kind "cur"}]
    (is (= 100000 (SUT/check-deposit fix 100000 cur)))
    (is (= 0 (SUT/check-deposit sav nil nil)))
    (is (= :deposit/below-minimum (second (SUT/check-deposit fix 99999 cur))))
    (is (= :deposit/no-source-account (second (SUT/check-deposit sav 100 nil))))
    (is (= :account/kind-held
           (second (SUT/check-account-open [cur] {:kind "cur" :name "E"}))))
    (is (= sav (SUT/check-account-open [cur] sav)))))

(deftest user-test
  (is (= {:first "Amara"
          :last "Okafor"
          :phone "+447700900123"
          :verification "verified"
          :member-since "2026-09-19T10:00:00Z"}
         (SUT/user
          {:given-name "Amara" :family-name "Okafor" :phone "+447700900123"}
          {:status "active" :created-at "2026-09-19T10:00:00Z"})))
  (is (= "pending" (:verification (SUT/user {} {:status "pending"})))))

(def ^:private secret "whsec_ZGVtby1kaWdpdGFsLWJhbmstdGVzdC1zZWNyZXQtMzJi")

(def ^:private vector-signature
  "HMAC-SHA256 of `whd.01test.1700000000.<body>` under the secret's
  key bytes, computed outside this code: the vector the receiver is
  held to."
  "v1,BT9/CgETT24Ig8AOe422LlivcFmdSR2Q3dpJ+r7wWV8=")

(def ^:private body
  (.getBytes "{\"notification-id\":\"whn.01test\",\"kind\":\"webhook.test\"}"
             "UTF-8"))

(def ^:private headers
  {"webhook-id" "whd.01test"
   "webhook-timestamp" "1700000000"
   "webhook-signature" vector-signature})

(def ^:private now 1700000001000)

(deftest verify-delivery-test
  (testing "the known vector signs and verifies"
    (is (= vector-signature (SUT/sign secret "whd.01test" "1700000000" body)))
    (is (= {:message-id "whd.01test" :timestamp 1700000000}
           (SUT/verify-delivery secret headers body now))))
  (testing "any one of the signatures the header carries is enough"
    (is (map? (SUT/verify-delivery secret
                                   (update headers
                                           "webhook-signature"
                                           (fn [s] (str "v1,AAAA " s)))
                                   body
                                   now))))
  (testing "a changed body, another secret, and a stale timestamp refuse"
    (let [refused (SUT/verify-delivery secret
                                       headers
                                       (.getBytes "{}" "UTF-8")
                                       now)]
      (is (error/unauthorized? refused))
      (is (= :webhook/invalid-signature (error/kind refused))))
    (is (= :webhook/invalid-signature
           (error/kind (SUT/verify-delivery
                        "whsec_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                        headers
                        body
                        now))))
    (is (= :webhook/stale
           (error/kind (SUT/verify-delivery
                        secret
                        headers
                        body
                        (+ now SUT/signature-tolerance-ms 1000))))))
  (testing "a missing header, and a bank with no secret, refuse"
    (is (= :webhook/unsigned
           (error/kind (SUT/verify-delivery secret
                                            (dissoc headers "webhook-id")
                                            body
                                            now))))
    (is (= :webhook/unsigned
           (error/kind (SUT/verify-delivery secret
                                            (assoc headers
                                                   "webhook-timestamp"
                                                   "soon")
                                            body
                                            now))))
    (is (= :webhook/no-secret
           (error/kind (SUT/verify-delivery nil headers body now))))))

(deftest notification-test
  (is (= {:id "whn.1"
          :kind "cash-account.opened"
          :resource-type "CashAccount"
          :resource-id "acc.1"
          :data {:name "Everyday"}}
         (SUT/notification {:notification-id "whn.1"
                            :kind "cash-account.opened"
                            :resource-type "CashAccount"
                            :resource-id "acc.1"
                            :data {:name "Everyday"}})))
  (is (error/rejection? (SUT/notification {:kind "cash-account.opened"}))))

(deftest notification-view-test
  (testing "an opened account is told in the customer's words"
    (is (= {:id "whn.1"
            :kind "cash-account.opened"
            :at "2026-09-19T10:00:00.000000Z"
            :headline "Rainy Day is open"
            :detail "Your new account is ready to use."
            :account "acc.1"}
           (SUT/notification-view {:id "whn.1"
                                   :kind "cash-account.opened"
                                   :received-at "2026-09-19T10:00:00.000000Z"}
                                  {:account-id "acc.1" :name "Rainy Day"}))))
  (testing "a kind the bank does not know is told as the change it names"
    (is (= "Something changed: party.status-changed"
           (:headline (SUT/notification-view {:id "whn.2"
                                              :kind "party.status-changed"}
                                             {}))))))
