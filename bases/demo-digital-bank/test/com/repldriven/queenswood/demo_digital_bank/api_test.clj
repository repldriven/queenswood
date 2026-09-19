(ns com.repldriven.queenswood.demo-digital-bank.api-test
  (:require
    [com.repldriven.queenswood.demo-digital-bank.system]

    [com.repldriven.queenswood.demo-digital-bank.api :as SUT]

    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.server.interface :as server]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]

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
  [base-url method path {:keys [body token]}]
  (let [res (http/request (cond-> {:method method
                                   :url (str base-url path)
                                   :headers (cond-> {"content-type"
                                                     "application/json"}
                                                    token
                                                    (assoc "authorization"
                                                           (str "Bearer "
                                                                token)))}

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
   (f (server/http-local-url (system/instance sys [:server :jetty-adapter])))))

(deftest sign-up-and-home-test
  (with-bank
   (fn [base-url]
     (let [token (sign-up base-url "07700 900123" "246810")]
       (testing "the session reads the home"
         (let [me (call base-url :get "/me" {:token token})]
           (is (= 200 (:status me)) (pr-str me))
           (is (= {:first "Amara" :last "Okafor" :verification "verified"}
                  (dissoc (get-in me [:body :user]) :member-since)))
           (is (= [] (get-in me [:body :accounts])))
           (is (= ["Everyday" "Rainy Day"]
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
   (fn [base-url]
     (sign-up base-url "07700 900200" "135790")
     (testing "a returning customer signs in"
       (let [session (call base-url
                           :post
                           "/sign-in"
                           {:body {:phone "07700 900200" :passcode "135790"}})]
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
                                     :passcode "000000"}})))))
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
                      {:body {:passcode "111111"}})]
         (is (= 409 (:status refused)))
         (is (= ":sign-up/invalid-status" (get-in refused [:body :type])))))
     (testing "an unknown sign-up is 404"
       (is (= 404
              (:status (call base-url
                             :post
                             "/sign-up/nope/code"
                             {:body {:code "123456"}}))))))))

(deftest openapi-test
  (with-bank
   (fn [base-url]
     (let [res (http/request {:method :get :url (str base-url "/openapi.json")})
           spec (http/res->edn res)]
       (is (= 200 (:status res)))
       (is (contains? (:paths spec) (keyword "/sign-up/{sign-up-id}/details")))
       (is (contains? (:paths spec) (keyword "/me")))))))
