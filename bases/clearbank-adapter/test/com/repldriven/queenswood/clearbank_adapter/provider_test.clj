(ns com.repldriven.queenswood.clearbank-adapter.provider-test
  (:require
    [com.repldriven.queenswood.clearbank-adapter.provider :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private declaration
  {:schemes ["fps"]
   :addresses ["scan"]
   :balances "pooled"
   :payee-check ["outbound" "inbound"]})

(deftest check-test
  (testing "a declaration the adapter carries passes"
    (is (nil? (SUT/check declaration))))
  (testing "a scheme the adapter does not carry refuses to start"
    (let [res (SUT/check (update declaration :schemes conj "chaps"))]
      (is (error/anomaly? res))
      (is (= :payment/unsupported-declaration (error/kind res)))
      (is (= {:schemes ["chaps"]} (:uncovered (error/payload res))))))
  (testing "a balance per account is not what the adapter holds"
    (is (= {:balances ["per-account"]}
           (:uncovered (error/payload (SUT/check (assoc declaration
                                                        :balances
                                                        "per-account")))))))
  (testing "a declaration leaving the new keys out reads as what it carries"
    (is (nil? (SUT/check (dissoc declaration :inbound :returns :screening)))))
  (testing "admission, returns and the bank's own screening are not ClearBank's"
    (is (= {:inbound ["admitted"] :returns ["inbound"] :screening ["bank"]}
           (:uncovered (error/payload (SUT/check (assoc declaration
                                                        :inbound "admitted"
                                                        :returns ["inbound"]
                                                        :screening
                                                        "bank"))))))))
