(ns com.repldriven.queenswood.demo-digital-bank.interface-test
  (:require
    [com.repldriven.mono.migrator.interface]
    [com.repldriven.mono.testcontainers.interface]

    [com.repldriven.queenswood.demo-digital-bank.interface :as SUT]

    [com.repldriven.queenswood.demo-digital-bank.domain :as domain]
    [com.repldriven.queenswood.demo-digital-bank.platform-stub :as stub]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as util]

    [clojure.test :refer [deftest is testing]]))

(def ^:private config "classpath:demo-digital-bank/application-test.yml")

(def ^:private details
  {:given-name "Amara"
   :family-name "Okafor"
   :date-of-birth "1994-03-12"
   :address {:building-number "12"
             :street "Mare Street"
             :town "London"
             :postcode "E8 3RH"}
   :national-identifier {:value "QQ123456C"}})

(defn- bank [sys] (system/instance sys [:demo-digital-bank :bank]))

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
                       _ (is (= ["Everyday" "Rainy Day" "1 Year Fixed"]
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

(def ^:private one-year-fixed "prd.00000000000000000000000003")

(defn- funded-everyday
  "An Everyday account for the customer with `balance` on it, seeded
  into the stand-in and recorded as theirs, answering its id."
  [bank state customer balance]
  (let [account-id (util/generate-id "acc")]
    (stub/seed-account
     state
     (assoc (platform-account account-id
                              (:party-id customer)
                              "Everyday"
                              "cur"
                              everyday
                              balance)
            :account-status
            "opened")
     [(leg account-id
           (util/generate-id "txn")
           "inbound-transfer"
           "credit"
           balance
           3)])
    (nom-test> [_
                (SUT/record-account
                 bank
                 customer
                 {:account-id account-id :product-kind "cur" :name "Everyday"})])
    account-id))

(deftest payee-check-test
  (with-test-system
   [sys config]
   (let [bank (bank sys)
         customer (customer bank (sign-up bank "07700 900400" details "1111"))
         check (fn [name]
                 (SUT/check-payee bank
                                  customer
                                  {:name name
                                   :sort-code "04-00-62"
                                   :account-number "12345678"}))]
     (testing "the outcome is the payee's bank's, in the app's words"
       (nom-test> [matched (check "Arthur Dent")
                   _ (is (= "match" (:outcome matched)))
                   _ (is (string? (:check-id matched)))
                   close (check "COP_CLOSEMATCH Jane A Doe")
                   _ (is (= "close-match" (:outcome close)))
                   _ (is (= "Jane A Doe" (:name-held close)))
                   none (check "COP_NOMATCH")
                   _ (is (= "no-match" (:outcome none)))
                   _ (is (nil? (:name-held none)))
                   down (check "COP_UNAVAILABLE")
                   _ (is (= "unavailable" (:outcome down)))])))))

(deftest payment-test
  (with-test-system
   [sys config]
   (let [bank (bank sys)
         state (state sys)
         customer (customer bank (sign-up bank "07700 900500" details "2222"))
         stranger (customer bank (sign-up bank "07700 900501" details "3333"))
         everyday-id (funded-everyday bank state customer 100000)
         key (str (util/uuidv7))
         request {:from everyday-id
                  :payee {:name "Arthur Dent"
                          :sort-code "04-00-62"
                          :account-number "12345678"}
                  :amount 2500
                  :reference "Towel"}]
     (testing "a payment to a new payee is sent, set aside, and kept"
       (nom-test> [payment (SUT/submit-payment bank customer key request)
                   _ (is (= "pending" (:status payment)))
                   _ (is (= 2500 (:amount payment)))
                   _ (is (= "Arthur Dent" (get-in payment [:payee :name])))
                   _ (is (= "04-00-62" (get-in payment [:payee :sort])))
                   _ (is (= 2500 (get-in payment [:payee :last-paid-amount])))
                   me (SUT/me bank customer)
                   _ (is (= 97500 (:balance (first (:accounts me)))))
                   _ (is (= 100000 (:posted (first (:accounts me)))))
                   _ (is (= ["Arthur Dent"] (map :name (:payees me))))
                   sent (first (:txns me))
                   _ (is (= "Arthur Dent" (:who sent)))
                   _ (is (= "pending" (:status sent)))
                   _ (is (= -2500 (:amount sent)))
                   _ (is (= "Payment" (:cat sent)))
                   _ (is (= 2 (count (:txns me))))]))
     (testing "the same key is the same payment, paid once"
       (nom-test> [again (SUT/submit-payment bank customer key request)
                   first-time (SUT/submit-payment bank customer key request)
                   _ (is (= (:id again) (:id first-time)))
                   me (SUT/me bank customer)
                   _ (is (= 97500 (:balance (first (:accounts me)))))
                   _ (is (= 2 (count (:txns me))))]))
     (testing "a settled payment reads as one posted row to the payee"
       (nom-test> [payment (SUT/submit-payment bank customer key request)
                   _ (stub/settle-outbound state (:id payment))
                   me (SUT/me bank customer)
                   _ (is (= 97500 (:balance (first (:accounts me)))))
                   _ (is (= 97500 (:posted (first (:accounts me)))))
                   settled (first (:txns me))
                   _ (is (= "Arthur Dent" (:who settled)))
                   _ (is (= "posted" (:status settled)))
                   _ (is (= -2500 (:amount settled)))
                   _ (is (= 2 (count (:txns me))))
                   _ (is (= 97500 (last (:spark (first (:accounts me))))))]))
     (testing "a payee already kept is paid by id, and a failed one vanishes"
       (nom-test> [me (SUT/me bank customer)
                   payee-id (:id (first (:payees me)))
                   payment (SUT/submit-payment bank
                                               customer
                                               (str (util/uuidv7))
                                               {:from everyday-id
                                                :payee {:id payee-id}
                                                :amount 1000})
                   _ (is (= payee-id (get-in payment [:payee :id])))
                   _ (is (= 1000 (get-in payment [:payee :last-paid-amount])))
                   _ (stub/fail-outbound state (:id payment))
                   after (SUT/me bank customer)
                   _ (is (= 97500 (:balance (first (:accounts after)))))
                   _ (is (= 2 (count (:txns after))))]))
     (testing "another customer's account, or payee, is not there"
       (let [refused (SUT/submit-payment bank stranger nil request)
             unknown (SUT/submit-payment bank
                                         customer
                                         nil
                                         (assoc request :payee {:id "nope"}))]
         (is (= :account/not-found (error/kind refused)))
         (is (= :payee/not-found (error/kind unknown)))))
     (testing "the platform's refusal comes back with its status"
       (let [refused (SUT/submit-payment bank
                                         customer
                                         nil
                                         (assoc request :amount 1000000))]
         (is (error/rejection? refused))
         (is (= :platform/refused (error/kind refused)))
         (is (= 422 (:status (error/payload refused)))))))))

(deftest open-account-and-transfer-test
  (with-test-system
   [sys config]
   (let [bank (bank sys)
         state (state sys)
         customer (customer bank (sign-up bank "07700 900600" details "4444"))
         everyday-id (funded-everyday bank state customer 100000)
         key (str (util/uuidv7))]
     (testing "a fixed-term account needs its minimum, and a kind held once"
       (let [small (SUT/open-account bank
                                     customer
                                     nil
                                     {:product-id one-year-fixed
                                      :deposit 50000})
             held (SUT/open-account bank customer nil {:product-id everyday})
             unknown (SUT/open-account bank customer nil {:product-id "nope"})]
         (is (= :deposit/below-minimum (error/kind small)))
         (is (= :account/kind-held (error/kind held)))
         (is (= :product/not-found (error/kind unknown)))))
     (testing "a savings account opens with its deposit moved from Everyday"
       (nom-test> [opened (SUT/open-account bank
                                            customer
                                            key
                                            {:product-id rainy-day
                                             :deposit 20000})
                   account (:account opened)
                   _ (is (= "Rainy Day" (:name account)))
                   _ (is (= "sav" (:kind account)))
                   _ (is (= "Easy-access saver · 4.10% AER" (:type account)))
                   _ (is (= "opened" (:status account)))
                   _ (is (= "04-00-75" (:sort account)))
                   _ (is (= 20000 (:balance account)))
                   _ (is (= 20000 (last (:spark account))))
                   _ (is (= 20000 (get-in opened [:deposit :amount])))
                   _ (is (= everyday-id (get-in opened [:deposit :from])))
                   again (SUT/open-account bank
                                           customer
                                           key
                                           {:product-id rainy-day
                                            :deposit 20000})
                   _ (is (= (:id account) (get-in again [:account :id])))
                   me (SUT/me bank customer)
                   _ (is (= ["Everyday" "Rainy Day"]
                            (map :name (:accounts me))))
                   _ (is (= [80000 20000] (map :balance (:accounts me))))
                   _ (is (= #{"Transfer to Rainy Day" "Transfer from Everyday"}
                            (set (map :who (take 2 (:txns me))))))
                   _ (is (= "Opening Rainy Day" (:ref (first (:txns me)))))]))
     (testing "money moves between the two, once per key"
       (nom-test> [me (SUT/me bank customer)
                   rainy-id (:id (second (:accounts me)))
                   key (str (util/uuidv7))
                   moved (SUT/transfer bank
                                       customer
                                       key
                                       {:from rainy-id
                                        :to everyday-id
                                        :amount 5000
                                        :reference "Back"})
                   _ (is (= rainy-id (:from moved)))
                   _ (is (= everyday-id (:to moved)))
                   _ (is (= 5000 (:amount moved)))
                   again (SUT/transfer bank
                                       customer
                                       key
                                       {:from rainy-id
                                        :to everyday-id
                                        :amount 5000
                                        :reference "Back"})
                   _ (is (= (:id moved) (:id again)))
                   after (SUT/me bank customer)
                   _ (is (= [85000 15000] (map :balance (:accounts after))))
                   _ (is (= "Transfer from Rainy Day"
                            (:who (first (:txns after)))))]))
     (testing "a transfer needs two different accounts of the customer's"
       (let [same (SUT/transfer bank
                                customer
                                nil
                                {:from everyday-id :to everyday-id :amount 1})
             elsewhere (SUT/transfer
                        bank
                        customer
                        nil
                        {:from everyday-id :to "acc.nope" :amount 1})]
         (is (= :transfer/same-account (error/kind same)))
         (is (= :account/not-found (error/kind elsewhere))))))))

(def ^:private secret "whsec_ZGVtby1kaWdpdGFsLWJhbmstdGVzdC1zZWNyZXQtMzJi")

(defn- delivery
  "A delivery of `envelope` as the platform would send it: the body's
  bytes and the Standard Webhooks headers, signed under `secret`."
  [message-id envelope secret]
  (let [body (.getBytes ^String (json/write-str envelope) "UTF-8")
        timestamp (str (quot (util/now) 1000))]
    {:body body
     :headers {"webhook-id" message-id
               "webhook-timestamp" timestamp
               "webhook-signature" (domain/sign secret
                                                message-id
                                                timestamp
                                                body)}}))

(defn- envelope
  [notification-id kind resource-type resource-id data]
  {:notification-id notification-id
   :kind kind
   :occurred-at "2026-09-19T10:00:00Z"
   :bank-id "bnk.00000000000000000000000001"
   :resource-type resource-type
   :resource-id resource-id
   :correlation-id "01998b6e-0e2e-7c3a-9a1e-5f6d2c4b8a02"
   :data data})

(defn- wait-for
  [pred]
  (loop [n 50]
    (cond (pred)
          true
          (zero? n)
          false
          :else
          (do (Thread/sleep 100) (recur (dec n))))))

(defn- receive
  [bank {:keys [headers body]}]
  (SUT/receive bank headers body))

(defn- leave!
  "How a stream is left: `events` returns only once `emit` throws."
  []
  ;; nosemgrep: no-raw-throw -- the throw is the exit
  (throw (ex-info "seen enough" {})))

(defn- read-stream
  "Open the customer's stream and take what it replays, leaving on the
  keep-alive that follows: the stream says nothing once subscribed,
  then what was not shown, then nothing again once the test system's
  short keep-alive passes. A stream with nothing to replay is left
  waiting, since only the bank stopping ends it, and what was taken by
  then is answered."
  [bank customer]
  (let [taken (atom [])
        stream (future (try (SUT/events bank
                                        customer
                                        (fn [event]
                                          (if event
                                            (swap! taken conj event)
                                            (when (seq @taken) (leave!)))))
                            (catch clojure.lang.ExceptionInfo _ nil)))]
    (deref stream 5000 ::waiting)
    @taken))

(deftest notifications-test
  (with-test-system
   [sys config]
   (let [bank (bank sys)
         state (state sys)
         ;; Bound as `owner`, since `customer` is the helper that resolves
         ;; one, and a map bound over it answers a lookup, not a customer.
         owner (customer bank (sign-up bank "07700 900500" details "5678"))
         account-id (util/generate-id "acc")
         account (platform-account account-id
                                   (:party-id owner)
                                   "Everyday"
                                   "cur"
                                   everyday
                                   0)
         _ (stub/seed-account state account [])
         opened (envelope "whn.00000000000000000000000001"
                          "cash-account.opened"
                          "CashAccount"
                          account-id
                          account)
         told (atom [])
         streaming (future (SUT/events
                            bank
                            owner
                            (fn [event] (when event (swap! told conj event)))))]
     (testing "a delivery that verifies is taken, and the customer is told"
       (nom-test> [taken (receive bank (delivery "whd.1" opened secret))
                   _ (is (= {:notification-id "whn.00000000000000000000000001"
                             :status "accepted"}
                            taken))])
       (is (wait-for (fn [] (seq @told))))
       (is (= {:id "whn.00000000000000000000000001"
               :kind "cash-account.opened"
               :headline "Everyday is open"
               :detail "Your new account is ready to use."
               :account account-id}
              (dissoc (first @told) :at))))
     (testing "a re-send of a notification already told is done"
       (nom-test> [again (receive bank (delivery "whd.2" opened secret))
                   _ (is (= "done" (:status again)))]))
     (testing "a delivery that does not verify is refused unheard"
       (let [refused
             (receive
              bank
              (delivery
               "whd.3"
               (assoc opened :notification-id "whn.00000000000000000000000002")
               "whsec_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))]
         (is (error/unauthorized? refused))
         (is (= :webhook/invalid-signature (error/kind refused)))))
     (testing "a delivery naming no notification is refused"
       (let [refused (receive
                      bank
                      (delivery "whd.4" {:kind "cash-account.opened"} secret))]
         (is (error/rejection? refused))
         (is (= :webhook/malformed (error/kind refused)))))
     (testing "a test notification is taken and told to nobody"
       (let [test-envelope (envelope "whn.00000000000000000000000003"
                                     "webhook.test" "WebhookEndpoint"
                                     "whe.00000000000000000000000001"
                                     {:endpoint-id
                                      "whe.00000000000000000000000001"})]
         (nom-test> [taken (receive bank
                                    (delivery "whd.5" test-envelope secret))
                     _ (is (= "accepted" (:status taken)))])
         (is (wait-for
              (fn []
                (= "done"
                   (:status
                    (receive bank (delivery "whd.6" test-envelope secret)))))))
         (is (= 1 (count @told)))))
     (testing "what was told while no stream was open is replayed on the next"
       (let [other (customer bank (sign-up bank "07700 900501" details "8765"))
             other-account (util/generate-id "acc")
             _ (stub/seed-account state
                                  (platform-account other-account
                                                    (:party-id other)
                                                    "Rainy Day"
                                                    "sav"
                                                    rainy-day
                                                    0)
                                  [])
             told-later
             (fn [notification-id message-id]
               (let [later (envelope notification-id
                                     "cash-account.opened"
                                     "CashAccount"
                                     other-account
                                     (get-in @state
                                             [:accounts
                                              other-account]))]
                 (receive bank (delivery message-id later secret))
                 (is (wait-for (fn []
                                 (= "done"
                                    (:status (receive bank
                                                      (delivery (str message-id
                                                                     "-again")
                                                                later
                                                                secret)))))))))]
         (told-later "whn.00000000000000000000000004" "whd.7")
         (is (= ["whn.00000000000000000000000004"]
                (map :id (read-stream bank other))))
         (testing "and once shown, not again"
           (told-later "whn.00000000000000000000000005" "whd.9")
           (is (= ["whn.00000000000000000000000005"]
                  (map :id (read-stream bank other)))))))
     (is (not (realized? streaming)) "the first stream is still held open"))))

(defn- internal-payment
  [payment-id creditor-account-id]
  {:payment-id payment-id
   :bank-id "bnk.00000000000000000000000001"
   :debtor-account-id "acc.house"
   :creditor-account-id creditor-account-id
   :currency "GBP"
   :amount 5000
   :transaction-id (str "txn." payment-id)
   :reference "Welcome"
   :business-day "2026-09-21"
   :created-at "2026-09-21T10:00:00Z"
   :updated-at "2026-09-21T10:00:00Z"})

(deftest money-arriving-is-told-to-the-account-holder-test
  (with-test-system
   [sys config]
   (let [bank (bank sys)
         state (state sys)
         owner (customer bank (sign-up bank "07700 900700" details "9012"))
         account-id (funded-everyday bank state owner 0)
         told (atom [])
         _ (future (SUT/events bank
                               owner
                               (fn [event]
                                 (when event (swap! told conj event)))))
         arrived (envelope "whn.00000000000000000000000011"
                           "payment.internal-settled" "InternalPayment"
                           "pmt.internal.1" (internal-payment "pmt.internal.1"
                                                              account-id))
         rewarded (envelope "whn.00000000000000000000000012"
                            "reward.paid" "Reward"
                            "rwd.1" {:reward-id "rwd.1"
                                     :bank-id "bnk.00000000000000000000000001"
                                     :account-id account-id
                                     :party-id (:party-id owner)
                                     :product-id everyday
                                     :version-id "prv.1"
                                     :kind "opening"
                                     :amount 5000
                                     :currency "GBP"
                                     :status "paid"
                                     :transaction-id "txn.reward.1"
                                     :paid-at "2026-09-21T11:00:00Z"
                                     :created-at "2026-09-21T11:00:00Z"
                                     :updated-at "2026-09-21T11:00:00Z"})
         nobodys (envelope "whn.00000000000000000000000013"
                           "payment.internal-settled" "InternalPayment"
                           "pmt.internal.2" (internal-payment "pmt.internal.2"
                                                              "acc.nobody"))]
     (testing "an internal payment into the account is told as money arriving"
       (nom-test> [taken (receive bank (delivery "whd.11" arrived secret))
                   _ (is (= "accepted" (:status taken)))])
       (is (wait-for (fn [] (seq @told))))
       (is (= {:id "whn.00000000000000000000000011"
               :kind "payment.internal-settled"
               :headline "£50.00 arrived"
               :detail "Welcome"
               :account account-id}
              (dissoc (first @told) :at))))
     (testing "a reward paid to the account is told as the welcome reward"
       (receive bank (delivery "whd.12" rewarded secret))
       (is (wait-for (fn [] (= 2 (count @told)))))
       (is (= "£50.00 welcome reward arrived" (:headline (second @told))))
       (is (= account-id (:account (second @told)))))
     (testing "a payment into an account nobody holds is taken, told to nobody"
       (receive bank (delivery "whd.13" nobodys secret))
       (is (wait-for
            (fn []
              (= "done"
                 (:status (receive bank (delivery "whd.14" nobodys secret)))))))
       (is (= 2 (count @told)))))))
