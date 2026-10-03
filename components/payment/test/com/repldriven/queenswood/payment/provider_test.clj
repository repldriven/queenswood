(ns com.repldriven.queenswood.payment.provider-test
  (:require
    [com.repldriven.queenswood.payment.provider :as SUT]

    [com.repldriven.mono.cache.interface :as cache]
    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(defn- counting
  [value]
  (let [calls (atom 0)]
    [calls
     (fn []
       (swap! calls inc)
       value)]))

(deftest cached-test
  (testing "a value is loaded once"
    (let [config {:cache (cache/create 60000)}
          [calls load] (counting {:declaration :modulr})]
      (is (= {:declaration :modulr}
             (SUT/cached config [:provider "bnk.1"] load)))
      (is (= {:declaration :modulr}
             (SUT/cached config [:provider "bnk.1"] load)))
      (is (= 1 @calls))))
  (testing "an anomaly is returned and loaded again next time"
    (let [config {:cache (cache/create 60000)}
          failure (error/fail :test/unavailable {:message "unavailable"})
          [calls load] (counting failure)]
      (is (= failure (SUT/cached config [:provider "bnk.1"] load)))
      (is (= failure (SUT/cached config [:provider "bnk.1"] load)))
      (is (= 2 @calls))))
  (testing "nothing found is loaded again next time"
    (let [config {:cache (cache/create 60000)}
          [calls load] (counting nil)]
      (is (nil? (SUT/cached config [:provider "bnk.1"] load)))
      (is (nil? (SUT/cached config [:provider "bnk.1"] load)))
      (is (= 2 @calls))))
  (testing "without a cache every lookup loads"
    (let [[calls load] (counting :value)]
      (is (= :value (SUT/cached {} [:provider "bnk.1"] load)))
      (is (= :value (SUT/cached {} [:provider "bnk.1"] load)))
      (is (= 2 @calls)))))
