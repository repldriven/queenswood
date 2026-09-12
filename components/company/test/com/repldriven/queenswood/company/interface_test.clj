(ns com.repldriven.queenswood.company.interface-test
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.company.interface :as company]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.test :refer [deftest is testing]]
    [clojure.walk :as walk]))

(def ^:private company
  {:company-number "SC998137"
   :company-name "SIRIUS CYBERNETICS CORPORATION LTD"
   :company-status "active"
   :type "ltd"
   :jurisdiction "england-wales"
   :date-of-creation "2009-02-11"
   :registered-office-address {:address-line-1 "42 Improbability Way"
                               :locality "London"
                               :postal-code "QZ1 9ZX"
                               :country "United Kingdom"}})

(defn- ->plain
  "Recursively convert protojure records to plain maps so equality
  comparisons against literal maps work in tests."
  [x]
  (walk/postwalk (fn [n] (if (record? n) (into {} n) n)) x))

(defn- store-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :meta-store])})

(deftest save-and-get-company-test
  (with-test-system [sys ["classpath:company/application-test.yml"]]
                    (let [config (store-config sys)]
                      (testing "round-trips a company keyed by company_number"
                        (nom-test> [_ (company/save-company config company)
                                    stored (company/get-company config
                                                                "SC998137")
                                    _ (is (= company (->plain stored)))]))
                      (testing "nil for a company that was never looked up"
                        (is (nil? (company/get-company config "99999999")))))))
