(ns com.repldriven.queenswood.modulr-adapter.provider-test
  (:require
    [com.repldriven.queenswood.modulr-adapter.provider :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private declaration
  {:schemes ["fps"]
   :addresses ["scan"]
   :balances "per-account"
   :payee-check ["outbound"]
   :inbound "notified"
   :returns []
   :screening "provider"})

(deftest check-test
  (testing "a declaration the adapter carries passes"
    (is (nil? (SUT/check declaration))))
  (testing "inbound name checks are not Modulr's to ask"
    (let [res (SUT/check (update declaration :payee-check conj "inbound"))]
      (is (= :payment/unsupported-declaration (error/kind res)))
      (is (= {:payee-check ["inbound"]} (:uncovered (error/payload res))))))
  (testing "a pooled balance is not what the adapter holds"
    (is (= {:balances ["pooled"]}
           (:uncovered (error/payload
                        (SUT/check (assoc declaration :balances "pooled")))))))
  (testing "admission, returns and the bank's own screening are not Modulr's"
    (is (= {:inbound ["admitted"] :returns ["inbound"] :screening ["bank"]}
           (:uncovered (error/payload (SUT/check (assoc declaration
                                                        :inbound "admitted"
                                                        :returns ["inbound"]
                                                        :screening
                                                        "bank"))))))))
