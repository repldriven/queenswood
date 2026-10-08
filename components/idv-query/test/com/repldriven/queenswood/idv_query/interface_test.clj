(ns com.repldriven.queenswood.idv-query.interface-test
  (:require
    [com.repldriven.queenswood.idv-query.interface :as SUT]

    [com.repldriven.mono.env.interface :as env]

    [clojure.test :refer [deftest is testing]]))

(def ^:private platform
  (:platform (env/config "classpath:idv-query/policies-test.yml" :test)))

(deftest verification-test
  (let [idv {:verification-id "idv.1"
             :status :idv-status-pending
             :criteria [{:verification :idv-verification-identity
                         :screening :idv-screening-unknown
                         :status :idv-criterion-status-established}
                        {:verification :idv-verification-address
                         :screening :idv-screening-unknown
                         :status :idv-criterion-status-outstanding}]}
        view (SUT/verification idv [platform])
        by-name
        (into {} (map (fn [c] [(SUT/criterion-name c) c])) (:criteria view))]
    (testing "a settled criterion reads as it was settled"
      (is (= :idv-criterion-status-established
             (get-in by-name ["identity" :status]))))
    (testing "an outstanding criterion carries the reason its deny gives"
      (is (= :idv-criterion-status-outstanding
             (get-in by-name ["address" :status])))
      (is (= "A person's address must be verified"
             (get-in by-name ["address" :reason]))))
    (testing "every criterion the platform requires is listed"
      (is (= #{"identity" "liveness" "claimed-identity" "address" "sanctions"
               "pep"}
             (set (keys by-name)))))
    (testing "a criterion nothing requires and nothing settled is left out"
      (let [allow-only [{:enabled true
                         :capabilities [{:effect :effect-allow
                                         :kind {:idv {:action
                                                      :idv-action-accept}}}]}]]
        (is (= ["identity"]
               (map SUT/criterion-name
                    (:criteria (SUT/verification idv allow-only)))))))))

(deftest session-test
  (let [ready {:status :idv-session-status-ready
               :hand-off {:type :idv-hand-off-type-url
                          :url "https://verify.example/x"
                          :expires-at 1000}}]
    (testing "a ready session carries its hand-off until it expires"
      (is (= "https://verify.example/x"
             (get-in (SUT/session ready 999) [:hand-off :url]))))
    (testing "an expired hand-off reads expired and is withheld"
      (let [view (SUT/session ready 1000)]
        (is (= :idv-session-status-expired (:status view)))
        (is (nil? (:hand-off view)))))
    (testing "an opening session has no hand-off"
      (is (nil? (:hand-off (SUT/session {:status :idv-session-status-opening}
                                        0)))))))
