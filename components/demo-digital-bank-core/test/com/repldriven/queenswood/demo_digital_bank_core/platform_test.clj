(ns com.repldriven.queenswood.demo-digital-bank-core.platform-test
  (:require
    [com.repldriven.mono.migrator.interface]
    [com.repldriven.mono.testcontainers.interface]

    [com.repldriven.queenswood.demo-digital-bank-core.platform :as SUT]

    [com.repldriven.queenswood.demo-digital-bank-core.platform-stub]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as util]

    [clojure.test :refer [deftest is testing]]))

(def ^:private config "classpath:demo-digital-bank-core/application-test.yml")

(def ^:private person
  {:type "person"
   :display-name "Ford Prefect"
   :given-name "Ford"
   :family-name "Prefect"
   :date-of-birth "1970-01-01"
   :nationality "GB"
   :address
   {:street "Mare Street" :town "London" :postcode "E8 3RH" :country "GBR"}
   :national-identifier
   {:type "national-insurance" :value "QQ000000A" :issuing-country "GB"}})

(deftest token-cache-test
  (with-test-system
   [sys config]
   (let [client (system/instance sys [:demo-digital-bank-core :platform])
         state (system/instance sys [:platform-stub :state])]
     (testing "one token serves every call until it nears expiry"
       (nom-test> [_ (SUT/list-products client)
                   _ (SUT/list-products client)
                   _ (SUT/list-products client)
                   _ (is (= 1 (:tokens @state)))
                   _ (is (= "tok-1" (SUT/bearer client)))]))
     (testing "a token within the margin of expiring is minted again"
       (swap! (:token client) assoc :expires-at (util/now))
       (nom-test> [_ (SUT/list-products client)
                   _ (is (= 2 (:tokens @state)))])))))

(deftest register-party-test
  (with-test-system
   [sys config]
   (let [client (system/instance sys [:demo-digital-bank-core :platform])
         key (str (util/uuidv7))]
     (testing "a repeat under the same key is the same party"
       (nom-test> [created (SUT/register-party client key person)
                   again (SUT/register-party client key person)
                   _ (is (= (:party-id created) (:party-id again)))
                   _ (is (= "pending" (:status created)))
                   fetched (SUT/get-party client (:party-id created))
                   _ (is (= "active" (:status fetched)))]))
     (testing "the platform's refusal comes back as a rejection"
       (let [refused (SUT/get-party client "pty.00000000000000000000000000")]
         (is (error/rejection? refused))
         (is (= :platform/refused (error/kind refused)))
         (is (= 404 (:status (error/payload refused)))))))))
