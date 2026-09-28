(ns com.repldriven.queenswood.form3-adapter.provider-test
  (:require
    [com.repldriven.queenswood.form3-adapter.provider :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private declaration
  {:schemes ["fps"]
   :addresses ["scan"]
   :balances "pooled"
   :payee-check ["outbound"]
   :inbound "admitted"
   :returns []
   :screening "bank"})

(deftest check-test
  (testing "the rails declaration passes" (is (nil? (SUT/check declaration))))
  (testing "a declaration silent on admission reads as notified, and fails"
    (is (= ["notified"]
           (get-in (error/payload (SUT/check (dissoc declaration :inbound)))
                   [:uncovered :inbound]))))
  (testing
    "a balance per account, inbound name checks and provider screening
are not Form3's"
    (is (= {:balances ["per-account"]
            :payee-check ["inbound"]
            :screening ["provider"]}
           (:uncovered (error/payload
                        (SUT/check (assoc declaration
                                          :balances "per-account"
                                          :payee-check ["outbound" "inbound"]
                                          :screening "provider"))))))))
