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

(def ^:private offered
  (SUT/providers {:default "modulr"
                  :providers {:modulr {:declaration {:balances "per-account"}
                                       :command-channel :modulr-command}
                              :form3 {:declaration {:balances "pooled"}
                                      :command-channel :form3-command}}}))

(deftest providers-test
  (testing "each entry carries its key"
    (is (= "form3" (get-in offered [:providers :form3 :provider]))))
  (testing "a default naming no provider is refused"
    (let [res (SUT/providers {:default "clearbank"
                              :providers {:modulr {:declaration {}}}})]
      (is (= :payment-provider/unknown-default (error/kind res)))
      (is (= ["modulr"] (:offered (error/payload res)))))))

(deftest default-test
  (is (= :modulr-command (:command-channel (SUT/default offered)))))

(deftest for-bank-test
  (testing "a bank recording a provider takes its entry"
    (is (= "form3"
           (:provider (SUT/for-bank offered
                                    {:providers [{:kind "idv" :provider "zyphe"}
                                                 {:kind "payment"
                                                  :provider "form3"}]})))))
  (testing "a bank recording none takes the default's"
    (is (= "modulr" (:provider (SUT/for-bank offered {})))))
  (testing "a provider not offered is refused"
    (let [res (SUT/for-bank offered
                            {:providers [{:kind "payment"
                                          :provider "clearbank"}]})]
      (is (= :payment-provider/unknown (error/kind res)))
      (is (= "clearbank" (:provider (error/payload res)))))))
