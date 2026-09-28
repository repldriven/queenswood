(ns com.repldriven.queenswood.payment-provider.interface-test
  (:require
    [com.repldriven.queenswood.payment-provider.interface :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private rails
  {:schemes #{"fps"}
   :balances #{"pooled"}
   :inbound #{"admitted"}
   :returns #{"inbound"}
   :screening #{"bank"}})

(defn- uncovered
  [declaration carries]
  (:uncovered (error/payload (SUT/check declaration carries))))

(deftest declared-test
  (testing "a key left out reads as a provider holding the money has it"
    (is
     (=
      {:balances "pooled" :inbound "notified" :returns [] :screening "provider"}
      (SUT/declared {:balances "pooled"}))))
  (testing "a key given is kept"
    (is (= "admitted" (:inbound (SUT/declared {:inbound "admitted"}))))))

(deftest check-test
  (testing "a declaration the adapter carries passes"
    (is (nil? (SUT/check {:schemes ["fps"]
                          :balances "pooled"
                          :inbound "admitted"
                          :returns ["inbound"]
                          :screening "bank"}
                         rails))))
  (testing "each value asked and not carried is named"
    (let [res (SUT/check {:schemes ["fps" "bacs"] :balances "per-account"}
                         rails)]
      (is (= :payment/unsupported-declaration (error/kind res)))
      (is (= ["bacs"] (get-in (error/payload res) [:uncovered :schemes])))
      (is (= ["per-account"]
             (get-in (error/payload res) [:uncovered :balances])))))
  (testing "a key left out is checked as its default"
    (is (= {:inbound ["notified"] :screening ["provider"]}
           (uncovered {:schemes ["fps"] :balances "pooled"} rails))))
  (testing "a key the adapter does not name is not checked"
    (is (nil? (SUT/check {:addresses ["iban"]} {:schemes #{"fps"}})))))
