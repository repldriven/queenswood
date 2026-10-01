(ns com.repldriven.queenswood.idv.interface-test
  (:require
    [com.repldriven.queenswood.idv.interface :as SUT]

    [com.repldriven.mono.env.interface :as env]
    [com.repldriven.mono.error.interface :as error :refer [nom->]]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]

    [clojure.test :refer [deftest is testing]]))

(def ^:private criteria (env/config "classpath:idv/criteria-test.yml" :test))

(deftest unmet-criteria-test
  (let [{:keys [platform micro idv-provider]} criteria]
    (testing "the platform policy requires all six of a person"
      (is (= [:idv-verification-identity :idv-verification-liveness
              :idv-verification-claimed-identity :idv-verification-address
              :idv-screening-sanctions :idv-screening-pep]
             (SUT/unmet-criteria [platform] {:verifies [] :screens []}))))
    (testing "the micro tier adds nothing to the platform floor"
      (is (= (SUT/unmet-criteria [platform] {:verifies [] :screens []})
             (SUT/unmet-criteria [platform micro] {:verifies [] :screens []}))))
    (testing "the deployed provider meets the platform and micro policies"
      (is (= [] (SUT/unmet-criteria [platform micro] idv-provider))))
    (testing "a provider that does not verify an address leaves it unmet"
      (is (= [:idv-verification-address]
             (SUT/unmet-criteria [platform]
                                 (update idv-provider
                                         :verifies
                                         (fn [vs] (remove #{"address"} vs)))))))
    (testing "check-criteria rejects naming what is unmet"
      (let [r (SUT/check-criteria [platform]
                                  (assoc idv-provider :screens ["sanctions"]))]
        (is (error/rejection? r))
        (is (= :idv/unsupported-criteria (error/kind r)))
        (is (= [:idv-screening-pep] (:unmet (error/payload r))))
        (is (= "The identity provider cannot establish pep"
               (:message (error/payload r))))))
    (testing "check-criteria passes a provider that meets them"
      (is (nil? (SUT/check-criteria [platform micro] idv-provider))))))

(deftest criteria-check-test
  (testing "starts when the provider meets the platform policy"
    (with-test-system [sys "classpath:idv/criteria-test.yml"]
                      (is (some? (system/instance sys
                                                  [:idv :criteria-check])))))
  (testing "refuses to start when a provider offered verifies no address"
    (let [result (nom-> (env/config "classpath:idv/criteria-unmet-test.yml"
                                    :test)
                        system/defs
                        system/start)]
      (is (error/anomaly? result))
      (is (some (fn [e] (= :idv/unsupported-criteria (:kind (ex-data e))))
                (take-while some?
                            (iterate ex-cause
                                     (:exception (error/payload result)))))))))

;; pending → accepted is no longer driven by an unconditional flip
;; in this brick; it now flows through the IDV-provider adapter
;; (bank-onfido-adapter) and the message-bus event handler in
;; `bank-idv.events`. The full chain is exercised by the monolith
;; integration test `idv_test.clj`.
