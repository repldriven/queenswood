(ns com.repldriven.queenswood.demo-digital-bank-core.interface-test
  (:require
    [com.repldriven.mono.migrator.interface]
    [com.repldriven.mono.testcontainers.interface]

    [com.repldriven.queenswood.demo-digital-bank-core.interface :as SUT]

    [com.repldriven.queenswood.demo-digital-bank-core.platform-stub :as stub]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as util]

    [clojure.test :refer [deftest is testing]]))

(def ^:private config "classpath:demo-digital-bank-core/application-test.yml")

(def ^:private details
  {:given-name "Amara"
   :family-name "Okafor"
   :date-of-birth "1994-03-12"
   :address {:building-number "12"
             :street "Mare Street"
             :town "London"
             :postcode "E8 3RH"}
   :national-identifier {:value "QQ123456C"}})

(defn- bank [sys] (system/instance sys [:demo-digital-bank-core :bank]))

(defn- state [sys] (system/instance sys [:platform-stub :state]))

(defn- sign-up
  "Walk a sign-up through to its session, which carries what each step
  answered as `:started`, `:verified` and `:registered`."
  [bank phone details passcode]
  (let [session (let-nom> [started (SUT/start-sign-up bank {:phone phone})
                           verified (SUT/verify-code bank
                                                     (:id started)
                                                     {:code "123456"})
                           registered (SUT/register-details bank
                                                            (:id started)
                                                            details)
                           session (SUT/choose-passcode bank
                                                        (:id started)
                                                        {:passcode passcode})]
                  (assoc session
                         :started started
                         :verified verified
                         :registered registered))]
    (is (not (error/anomaly? session)) (pr-str session))
    session))

(defn- customer
  [bank session]
  (let [customer (SUT/authenticate bank (:token session))]
    (is (map? customer) (pr-str customer))
    customer))

(defn- leg
  [account-id transaction-id type side amount days-ago]
  {:leg-id (util/generate-id "leg")
   :transaction-id transaction-id
   :transaction-type type
   :status "posted"
   :account-id account-id
   :balance-type "default"
   :balance-status "posted"
   :side side
   :amount amount
   :currency "GBP"
   :created-at (str (java.time.Instant/ofEpochMilli
                     (- (util/now) (* days-ago 86400000))))})

(deftest sign-up-to-session-test
  (with-test-system
   [sys config]
   (let [bank (bank sys)]
     (testing "a sign-up ends in a session the customer can use"
       (let [session (sign-up bank "07700 900123" details "246810")]
         (is (= "code-sent" (get-in session [:started :status])))
         (is (= "verified" (get-in session [:verified :status])))
         (is (= "registered" (get-in session [:registered :status])))
         (is (string? (get-in session [:registered :party-id])))
         (is (= "pending" (get-in session [:registered :verification])))
         (is (string? (:token session)))
         (let [customer (customer bank session)]
           (is (= "Amara" (:given-name customer)))
           (is (= "+447700900123" (:phone customer)))
           (nom-test> [me (SUT/me bank customer)
                       _ (is (= {:first "Amara" :last "Okafor"}
                                (select-keys (:user me) [:first :last])))
                       _ (is (= "verified" (get-in me [:user :verification])))
                       _ (is (= [] (:accounts me)))
                       _ (is (= ["Everyday" "Rainy Day"]
                                (map :name (:products me))))]))))
     (testing "a step out of order is refused with what was expected"
       (let [started (SUT/start-sign-up bank {:phone "07700 900124"})
             refused (SUT/register-details bank (:id started) details)]
         (is (error/rejection? refused))
         (is (= :sign-up/invalid-status (error/kind refused)))
         (is (= "verified" (:allowed (error/payload refused))))))
     (testing "a wrong code is refused and the sign-up stays where it was"
       (let [started (SUT/start-sign-up bank {:phone "07700 900125"})
             refused (SUT/verify-code bank (:id started) {:code "000000"})]
         (is (= :sign-up/invalid-code (error/kind refused)))
         (nom-test> [verified
                     (SUT/verify-code bank (:id started) {:code "123456"})
                     _ (is (= "verified" (:status verified)))])))
     (testing "a given name the simulator rejects reads back as rejected"
       (let [session (sign-up bank
                              "07700 900126"
                              (assoc details :given-name "Reject")
                              "111111")
             customer (customer bank session)]
         (nom-test> [me (SUT/me bank customer)
                     _ (is (= "rejected" (get-in me [:user :verification])))]))))))

(deftest sign-in-test
  (with-test-system
   [sys config]
   (let [bank (bank sys)]
     (sign-up bank "07700 900200" details "135790")
     (testing "the passcode opens a session, spelled however the number is"
       (nom-test> [session (SUT/sign-in bank
                                        {:phone "+44 7700 900200"
                                         :passcode "135790"})
                   customer (SUT/authenticate bank (:token session))
                   _ (is (= "+447700900200" (:phone customer)))]))
     (testing "a wrong passcode and an unknown number refuse alike"
       (let [wrong (SUT/sign-in bank {:phone "07700 900200" :passcode "000000"})
             unknown (SUT/sign-in bank
                                  {:phone "07700 900299" :passcode "135790"})]
         (is (error/unauthorized? wrong))
         (is (error/unauthorized? unknown))
         (is (= (error/kind wrong) (error/kind unknown)))))
     (testing "a session that was signed out no longer resolves"
       (let [session (SUT/sign-in bank
                                  {:phone "07700 900200" :passcode "135790"})]
         (is (string? (:token session)) (pr-str session))
         (is (nil? (SUT/sign-out bank (:token session))))
         (is (error/unauthorized? (SUT/authenticate bank (:token session))))))
     (testing "no token is no session"
       (is (error/unauthorized? (SUT/authenticate bank nil)))
       (is (error/unauthorized? (SUT/authenticate bank "ses-not-a-token")))))))

(def ^:private everyday "prd.00000000000000000000000001")

(def ^:private rainy-day "prd.00000000000000000000000002")

(defn- platform-account
  [account-id party-id name kind product balance]
  {:account-id account-id
   :party-id party-id
   :name name
   :currency "GBP"
   :product-id product
   :product-type (if (= "cur" kind) "current" "savings")
   :account-status "opened"
   :payment-addresses [{:scheme "scan"
                        :scan {:sort-code "040075"
                               :account-number "31908240"}}]
   :posted-balance {:value balance :currency "GBP"}
   :available-balance {:value balance :currency "GBP"}})

(deftest home-read-test
  (with-test-system
   [sys config]
   (let [bank (bank sys)
         state (state sys)
         customer (customer bank (sign-up bank "07700 900300" details "112233"))
         stranger (customer bank (sign-up bank "07700 900301" details "445566"))
         current (util/generate-id "acc")
         savings (util/generate-id "acc")
         transfer (util/generate-id "txn")]
     (stub/seed-account
      state
      (platform-account current
                        (:party-id customer)
                        "Everyday"
                        "cur"
                        everyday
                        241862)
      [(leg current
            (util/generate-id "txn")
            "inbound-transfer" "credit"
            286000 8) (leg current transfer "internal-transfer" "debit" 20000 1)
       (leg current
            (util/generate-id "txn")
            "outbound-transfer" "debit"
            685 0)])
     (stub/seed-account
      state
      (platform-account savings
                        (:party-id customer)
                        "Rainy Day"
                        "sav"
                        rainy-day
                        620000)
      [(leg savings transfer "internal-transfer" "credit" 20000 1)])
     (nom-test> [_ (SUT/record-account
                    bank
                    customer
                    {:account-id current :product-kind "cur" :name "Everyday"})
                 _ (SUT/record-account bank
                                       customer
                                       {:account-id savings
                                        :product-kind "sav"
                                        :name "Rainy Day"})])
     (testing "the home read carries the customer's accounts and legs"
       (nom-test> [me (SUT/me bank customer)
                   accounts (:accounts me)
                   _ (is (= ["Everyday" "Rainy Day"] (map :name accounts)))
                   first-account (first accounts)
                   _ (is (= "Current account" (:type first-account)))
                   _ (is (= "04-00-75" (:sort first-account)))
                   _ (is (= "31908240" (:num first-account)))
                   _ (is (= 241862 (:balance first-account)))
                   _ (is (= 7 (count (:spark first-account))))
                   _ (is (= 241862 (last (:spark first-account))))
                   _ (is (= (+ 241862 685 20000)
                            (first (:spark first-account))))
                   second-account (second accounts)
                   _ (is (= "Easy-access saver · 4.10% AER"
                            (:type second-account)))
                   txns (:txns me)
                   _ (is (= 4 (count txns)))
                   _ (is (= ["Payment" "Received"]
                            [(:who (first txns)) (:who (last txns))]))
                   _ (is (= #{"Transfer to Rainy Day" "Transfer from Everyday"}
                            (set (map :who (subvec txns 1 3)))))
                   _ (is (= [-685 286000]
                            [(:amount (first txns)) (:amount (last txns))]))
                   _ (is (= #{-20000 20000}
                            (set (map :amount (subvec txns 1 3)))))]))
     (testing "another customer's account is not there"
       (nom-test> [held (SUT/customer-account bank customer current)
                   _ (is (= current (:account-id held)))])
       (let [refused (SUT/customer-account bank stranger current)]
         (is (error/rejection? refused))
         (is (= :account/not-found (error/kind refused))))))))
