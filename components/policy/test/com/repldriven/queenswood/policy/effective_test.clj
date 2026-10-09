(ns com.repldriven.queenswood.policy.effective-test
  (:require
    [com.repldriven.queenswood.policy.effective :as SUT]

    [clojure.test :refer [deftest is testing]]))

(defn- policy
  [tier id name &
   {:keys [capabilities limits status]
    :or {capabilities [] limits [] status :policy-status-active}}]
  {:policy-id id
   :name name
   :status status
   :labels {"tier" tier}
   :capabilities capabilities
   :limits limits})

(defn- cap
  [effect domain action]
  {:effect effect :kind {domain {:action action}}})

(defn- max-count-limit
  [domain value]
  {:kind {domain {}}
   :bound {:kind {:max {:aggregate {:kind {:count {:value value
                                                   :window
                                                   :time-window-instant}}}}}}})

(deftest capability-deny-wins-test
  (testing
    "platform allow + micro deny on the same action resolves to deny,
            carrying the micro origin"
    (let [policies [(policy "platform"
                            "pol.platform" "Platform"
                            :capabilities
                            [(cap :effect-allow :bank :bank-action-create)])
                    (policy "micro"
                            "pol.micro" "Micro"
                            :capabilities
                            [(cap :effect-deny :bank :bank-action-create)])]
          {:keys [capabilities]} (SUT/resolve-effective policies)]
      (is (= 1 (count capabilities)))
      (is (= :effect-deny (:effect (first capabilities))))
      (is (= "micro" (get-in (first capabilities) [:origin :tier])))
      (is (= "pol.micro" (get-in (first capabilities) [:origin :policy-id]))))))

(deftest capability-distinct-scopes-test
  (testing "an allow and a deny on different actions stay distinct"
    (let [policies [(policy "platform"
                            "pol.platform" "Platform"
                            :capabilities [(cap :effect-allow
                                                :cash-account
                                                :cash-account-action-open)
                                           (cap :effect-deny
                                                :cash-account
                                                :cash-account-action-close)])]
          {:keys [capabilities]} (SUT/resolve-effective policies)]
      (is (= 2 (count capabilities))))))

(deftest disabled-policy-ignored-test
  (testing "a disabled policy contributes nothing"
    (let [policies [(policy "micro"
                            "pol.micro" "Micro"
                            :status :policy-status-disabled
                            :capabilities
                            [(cap :effect-deny :bank :bank-action-create)])]
          {:keys [capabilities]} (SUT/resolve-effective policies)]
      (is (= [] capabilities)))))

(deftest limit-passthrough-test
  (testing "a single platform limit passes through with its origin"
    (let [policies [(policy "platform"
                            "pol.platform" "Platform"
                            :limits [(max-count-limit :cash-account 1000)])]
          {:keys [limits]} (SUT/resolve-effective policies)]
      (is (= 1 (count limits)))
      (is (= "platform" (get-in (first limits) [:origin :tier]))))))

(deftest limit-most-restrictive-test
  (testing
    "overlapping max limits on the same scope resolve to the smaller
            ceiling, carrying that policy's origin"
    (let [policies [(policy "platform"
                            "pol.platform" "Platform"
                            :limits [(max-count-limit :cash-account 1000)])
                    (policy "micro"
                            "pol.micro" "Micro"
                            :limits [(max-count-limit :cash-account 10)])]
          {:keys [limits]} (SUT/resolve-effective policies)]
      (is (= 1 (count limits)))
      (is (= 10
             (get-in (first limits)
                     [:bound :kind :max :aggregate :kind :count :value])))
      (is (= "micro" (get-in (first limits) [:origin :tier]))))))
