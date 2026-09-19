(ns com.repldriven.queenswood.demo-digital-bank.api-test
  (:require
    [com.repldriven.queenswood.demo-digital-bank.system]

    [com.repldriven.queenswood.demo-digital-bank.api :as SUT]

    [com.repldriven.queenswood.demo-digital-bank-core.interface :as bank]
    ;; The platform stand-in is the core brick's test tree, and seeding an
    ;; opened account with money on it there is how a route test gets a
    ;; customer who can pay without booting the platform.

    ;; enforce-idioms: brick-test-scope -- stand-in from the core brick
    [com.repldriven.queenswood.demo-digital-bank-core.platform-stub :as stub]

    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.server.interface :as server]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]
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

(defn- call
  [base-url method path {:keys [body token key]}]
  (let [res (http/request (cond-> {:method method
                                   :url (str base-url path)
                                   :headers (cond-> {"content-type"
                                                     "application/json"}
                                                    token
                                                    (assoc "authorization"
                                                           (str "Bearer "
                                                                token))

                                                    key
                                                    (assoc "idempotency-key"
                                                           key))}

                                  body
                                  (assoc :body (json/write-str body))))]
    {:status (:status res) :body (http/res->edn res)}))

(defn- sign-up
  "Walk the sign-up routes through to a session, answering its token."
  [base-url phone passcode]
  (let [started (call base-url :post "/sign-up" {:body {:phone phone}})
        id (get-in started [:body :id])
        coded (call base-url
                    :post
                    (str "/sign-up/" id "/code")
                    {:body {:code "123456"}})
        registered (call base-url
                         :post
                         (str "/sign-up/" id "/details")
                         {:body details})
        session (call base-url
                      :post
                      (str "/sign-up/" id "/passcode")
                      {:body {:passcode passcode}})]
    (is (= 201 (:status started)) (pr-str started))
    (is (= 200 (:status coded)) (pr-str coded))
    (is (= 200 (:status registered)) (pr-str registered))
    (is (= "pending" (get-in registered [:body :verification])))
    (is (= 201 (:status session)) (pr-str session))
    (get-in session [:body :token])))

(defn- with-bank
  [f]
  (with-test-system
   [sys [config #(assoc-in % [:system/defs :server :handler] SUT/app)]]
   (f (server/http-local-url (system/instance sys [:server :jetty-adapter]))
      sys)))

(defn- funded-everyday
  "An opened Everyday account with `balance` on it, seeded into the
  stand-in and recorded as the session's customer's, answering its id."
  [sys token balance]
  (let [bank (system/instance sys [:demo-digital-bank-core :bank])
        state (system/instance sys [:platform-stub :state])
        customer (bank/authenticate bank token)
        account-id (util/generate-id "acc")]
    (stub/seed-account state
                       {:account-id account-id
                        :party-id (:party-id customer)
                        :name "Everyday"
                        :currency "GBP"
                        :product-id "prd.00000000000000000000000001"
                        :product-type "current"
                        :account-status "opened"
                        :payment-addresses [{:scheme "scan"
                                             :scan {:sort-code "040075"
                                                    :account-number
                                                    "31908240"}}]
                        :posted-balance {:value balance :currency "GBP"}
                        :available-balance {:value balance :currency "GBP"}}
                       [])
    (bank/record-account bank
                         customer
                         {:account-id account-id
                          :product-kind "cur"
                          :name "Everyday"})
    account-id))

(deftest sign-up-and-home-test
  (with-bank
   (fn [base-url _]
     (let [token (sign-up base-url "07700 900123" "2468")]
       (testing "the session reads the home"
         (let [me (call base-url :get "/me" {:token token})]
           (is (= 200 (:status me)) (pr-str me))
           (is (= {:first "Amara"
                   :last "Okafor"
                   :phone "+447700900123"
                   :verification "verified"}
                  (dissoc (get-in me [:body :user]) :member-since)))
           (is (= [] (get-in me [:body :accounts])))
           (is (= ["Everyday" "Rainy Day" "1 Year Fixed"]
                  (map :name (get-in me [:body :products]))))))
       (testing "no session is 401, in problem details"
         (let [refused (call base-url :get "/me" {})]
           (is (= 401 (:status refused)))
           (is (= "UNAUTHORIZED" (get-in refused [:body :title])))))
       (testing "signing out ends the session"
         (is (= 204 (:status (call base-url :post "/sign-out" {:token token}))))
         (is (= 401 (:status (call base-url :get "/me" {:token token})))))))))

(deftest sign-in-and-refusals-test
  (with-bank
   (fn [base-url _]
     (sign-up base-url "07700 900200" "1357")
     (testing "a returning customer signs in"
       (let [session (call base-url
                           :post
                           "/sign-in"
                           {:body {:phone "07700 900200" :passcode "1357"}})]
         (is (= 201 (:status session)) (pr-str session))
         (is (= 200
                (:status (call base-url
                               :get
                               "/me"
                               {:token (get-in session [:body :token])}))))))
     (testing "a wrong passcode is 401"
       (is (= 401
              (:status (call base-url
                             :post
                             "/sign-in"
                             {:body {:phone "07700 900200"
                                     :passcode "0000"}})))))
     (testing "a malformed body is 400"
       (is (= 400
              (:status (call base-url
                             :post
                             "/sign-in"
                             {:body {:phone "07700 900200" :passcode "12"}})))))
     (testing "a step out of order is 409 naming the step expected"
       (let [started
             (call base-url :post "/sign-up" {:body {:phone "07700 900201"}})
             refused (call
                      base-url
                      :post
                      (str "/sign-up/" (get-in started [:body :id]) "/passcode")
                      {:body {:passcode "1111"}})]
         (is (= 409 (:status refused)))
         (is (= ":sign-up/invalid-status" (get-in refused [:body :type])))))
     (testing "an unknown sign-up is 404"
       (is (= 404
              (:status (call base-url
                             :post
                             "/sign-up/nope/code"
                             {:body {:code "123456"}}))))))))

(deftest payments-test
  (with-bank
   (fn [base-url sys]
     (let [token (sign-up base-url "07700 900300" "3579")
           everyday (funded-everyday sys token 100000)
           payee {:name "Arthur Dent"
                  :sort-code "04-00-62"
                  :account-number "12345678"}
           key (str (util/uuidv7))]
       (testing "a payee check answers the outcome"
         (let [checked
               (call base-url :post "/payee-checks" {:token token :body payee})]
           (is (= 200 (:status checked)) (pr-str checked))
           (is (= "match" (get-in checked [:body :outcome])))))
       (testing "a payment under a key is made once"
         (let [payment
               {:from everyday :payee payee :amount 2500 :reference "Towel"}
               sent (call base-url
                          :post
                          "/payments"
                          {:token token :key key :body payment})
               again (call base-url
                           :post
                           "/payments"
                           {:token token :key key :body payment})
               me (call base-url :get "/me" {:token token})]
           (is (= 201 (:status sent)) (pr-str sent))
           (is (= "pending" (get-in sent [:body :status])))
           (is (= "Arthur Dent" (get-in sent [:body :payee :name])))
           (is (= (get-in sent [:body :id]) (get-in again [:body :id])))
           (is (= 97500 (get-in me [:body :accounts 0 :balance])))
           (is (= ["Arthur Dent"] (map :name (get-in me [:body :payees]))))
           (is (= "Arthur Dent" (get-in me [:body :txns 0 :who])))
           (is (= "pending" (get-in me [:body :txns 0 :status])))))
       (testing "an account opens with its deposit, and money moves back"
         (let [opened (call base-url
                            :post
                            "/accounts"
                            {:token token
                             :key (str (util/uuidv7))
                             :body {:product-id "prd.00000000000000000000000002"
                                    :deposit 20000}})
               rainy (get-in opened [:body :account :id])
               moved (call base-url
                           :post
                           "/transfers"
                           {:token token
                            :key (str (util/uuidv7))
                            :body {:from rainy :to everyday :amount 5000}})
               me (call base-url :get "/me" {:token token})]
           (is (= 201 (:status opened)) (pr-str opened))
           (is (= "Rainy Day" (get-in opened [:body :account :name])))
           (is (= 20000 (get-in opened [:body :deposit :amount])))
           (is (= 201 (:status moved)) (pr-str moved))
           (is (= [82500 15000] (map :balance (get-in me [:body :accounts]))))))
       (testing
         "the bank's rules refuse as 422, and a stranger's account is 404"
         (let [same (call base-url
                          :post
                          "/transfers"
                          {:token token
                           :body {:from everyday :to everyday :amount 1}})
               small (call base-url
                           :post
                           "/accounts"
                           {:token token
                            :body {:product-id "prd.00000000000000000000000003"
                                   :deposit 1}})
               other (sign-up base-url "07700 900301" "8642")
               refused (call base-url
                             :post
                             "/payments"
                             {:token other
                              :body {:from everyday :payee payee :amount 1}})
               malformed (call base-url
                               :post
                               "/payments"
                               {:token token
                                :body {:from everyday :payee payee :amount 0}})]
           (is (= 422 (:status same)))
           (is (= ":transfer/same-account" (get-in same [:body :type])))
           (is (= 422 (:status small)))
           (is (= 404 (:status refused)))
           (is (= 400 (:status malformed)))))))))

(deftest openapi-test
  (with-bank
   (fn [base-url _]
     (let [res (http/request {:method :get :url (str base-url "/openapi.json")})
           spec (http/res->edn res)]
       (is (= 200 (:status res)))
       (is (contains? (:paths spec) (keyword "/sign-up/{sign-up-id}/details")))
       (is (contains? (:paths spec) (keyword "/me")))
       (is (contains? (:paths spec) (keyword "/payments")))))))
