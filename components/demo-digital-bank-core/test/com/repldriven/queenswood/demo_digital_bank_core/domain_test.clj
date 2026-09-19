(ns com.repldriven.queenswood.demo-digital-bank-core.domain-test
  (:require
    [com.repldriven.queenswood.demo-digital-bank-core.domain :as SUT]

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
                                 {"cur" "Everyday" "sav" "Rainy Day"})]
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
                             {})))))

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
