(ns com.repldriven.queenswood.company.interface-test
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.company.interface :as company]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.test :refer [deftest is testing]]))

(def ^:private registry :company-registry-uk-companies-house)

(def ^:private profile
  {:registry registry
   :company-number "SC998137"
   :name "SIRIUS CYBERNETICS CORPORATION LTD"
   :status "active"
   :company-type "ltd"
   :jurisdiction "england-wales"
   :incorporated-on 14286
   :registered-office-address {:address-line-1 "42 Improbability Way"
                               :locality "London"
                               :postal-code "QZ1 9ZX"
                               :country "United Kingdom"}})

(defn- store-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :meta-store])})

(deftest save-and-get-company-test
  (with-test-system
   [sys ["classpath:company/application-test.yml"]]
   (let [config (store-config sys)]
     (testing "round-trips a company keyed by registry and number"
       (nom-test> [saved (company/save-company config profile)
                   stored (company/get-company config registry "SC998137")
                   _ (is (= saved stored))
                   _ (is (= profile (dissoc stored :created-at :updated-at)))]))
     (testing "a second lookup keeps when the company was first seen"
       (nom-test> [first-seen (company/get-company config registry "SC998137")
                   _ (company/save-company config
                                           (assoc profile :status "dissolved"))
                   stored (company/get-company config registry "SC998137")
                   _ (is (= "dissolved" (:status stored)))
                   _ (is (= (:created-at first-seen) (:created-at stored)))]))
     (testing "a profile with no optional fields reads back without them"
       (let [bare (select-keys profile [:registry :name :status :company-type])]
         (nom-test> [_ (company/save-company
                        config
                        (assoc bare :company-number "00000001"))
                     stored (company/get-company config registry "00000001")
                     _ (is (= (assoc bare :company-number "00000001")
                              (dissoc stored :created-at :updated-at)))])))
     (testing "nil for a company that was never looked up"
       (is (nil? (company/get-company config registry "99999999")))))))
