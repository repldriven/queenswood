(ns com.repldriven.queenswood.policy.limit-test
  (:require
    [com.repldriven.queenswood.policy.limit :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(defn- amount-max-policy
  "One policy with a single `kind` amount max limit of `value` minor
  units in `currency` over `window`."
  [kind value currency window]
  [{:enabled true
    :limits [{:kind {kind {}}
              :bound {:kind {:max {:aggregate
                                   {:kind {:amount
                                           {:value {:value value
                                                    :currency currency}
                                            :window window}}}}}}
              :reason "cap"}]}])

(defn- amount-request
  [window value currency]
  {:aggregate :amount :window window :value {:value value :currency currency}})

(deftest amount-max-limit-test
  (let [policies (amount-max-policy :outbound-payment 1000000
                                    "GBP" :time-window-instant)]
    (testing "an amount at the cap passes"
      (is (true? (SUT/check
                  policies
                  :outbound-payment
                  (amount-request :time-window-instant 1000000 "GBP")))))
    (testing "an amount over the cap is rejected"
      (let [result (SUT/check
                    policies
                    :outbound-payment
                    (amount-request :time-window-instant 1000001 "GBP"))]
        (is (error/rejection? result))
        (is (= :policy/limit-exceeded (error/kind result)))))
    (testing "a different currency does not match the GBP cap"
      (is (true? (SUT/check
                  policies
                  :outbound-payment
                  (amount-request :time-window-instant 9999999 "EUR")))))
    (testing "a different window does not match the instant cap"
      (is (true? (SUT/check
                  policies
                  :outbound-payment
                  (amount-request :time-window-daily 9999999 "GBP")))))
    (testing "a count request does not match an amount limit"
      (is (true? (SUT/check policies
                            :outbound-payment
                            {:aggregate :count
                             :window :time-window-daily
                             :value 9999999}))))))

(defn- migration-policy
  "The two migration limits a tier carries: a cohort cap on a commit,
  and a daily cap on previews. One kind, told apart by action — the
  same shape interest uses for accrue and capitalize."
  [commit-cap preview-cap]
  [{:enabled true
    :limits
    [{:kind {:cash-account-migration
             {:filters [{:action :cash-account-migration-action-commit}]}}
      :bound {:kind {:max {:aggregate {:kind {:count
                                              {:value commit-cap
                                               :window
                                               :time-window-instant}}}}}}
      :reason "cohort cap"}
     {:kind {:cash-account-migration
             {:filters [{:action :cash-account-migration-action-preview}]}}
      :bound {:kind {:max {:aggregate {:kind {:count
                                              {:value preview-cap
                                               :window
                                               :time-window-daily}}}}}}
      :reason "preview cap"}]}])

(defn- commit-request
  [value]
  {:aggregate :count
   :window :time-window-instant
   :action :cash-account-migration-action-commit
   :value value})

(defn- preview-request
  [value]
  {:aggregate :count
   :window :time-window-daily
   :action :cash-account-migration-action-preview
   :value value})

(deftest migration-limit-test
  (let [policies (migration-policy 100 10)]
    (testing "a cohort at the cap may move"
      (is (true?
           (SUT/check policies :cash-account-migration (commit-request 100)))))
    (testing "a cohort over the cap is refused"
      (let [result
            (SUT/check policies :cash-account-migration (commit-request 101))]
        (is (error/rejection? result))
        (is (= :policy/limit-exceeded (error/kind result)))))
    (testing "the day's tenth preview is allowed and the eleventh is not"
      (is (true?
           (SUT/check policies :cash-account-migration (preview-request 10))))
      (is (error/rejection?
           (SUT/check policies :cash-account-migration (preview-request 11)))))
    (testing "the two caps do not bleed into each other"
      ;; Both are counts on the same kind, so the action filter is the
      ;; only thing keeping a cohort of 50 from being read against the
      ;; preview cap of 10.
      (is (true?
           (SUT/check policies :cash-account-migration (commit-request 50))))
      (is (error/rejection?
           (SUT/check policies :cash-account-migration (preview-request 50)))))
    (testing "an unrelated domain is untouched by either"
      (is (true? (SUT/check policies :cash-account (commit-request 999)))))))

(defn- term-deposit-count-policy
  "The micro tier's filtered cash-account limit as it reads back off
  the store: proto2 has filled the two terms the seed left unset with
  their zero values."
  []
  [{:enabled true
    :limits [{:kind {:cash-account
                     {:filters [{:product-type
                                 :product-type-sub-ledger-term-deposit
                                 :account-type :account-type-unknown
                                 :currency ""}]}}
              :bound {:kind {:max {:aggregate
                                   {:kind {:count
                                           {:value 10
                                            :window
                                            :time-window-instant}}}}}}
              :reason "ten term deposits"}]}])

(defn- cash-account-count-request
  [product-type account-type currency value]
  {:aggregate :count
   :window :time-window-instant
   :product-type product-type
   :account-type account-type
   :currency currency
   :value value})

(deftest cash-account-filter-unset-terms-test
  (let [policies (term-deposit-count-policy)]
    (testing "an unset term matches whatever the request carries"
      (let [result (SUT/check policies
                              :cash-account
                              (cash-account-count-request
                               :product-type-sub-ledger-term-deposit
                               :account-type-business
                               "GBP" 11))]
        (is (error/rejection? result))
        (is (= :policy/limit-exceeded (error/kind result)))))
    (testing "and matches a second currency the same way"
      (is (error/rejection? (SUT/check policies
                                       :cash-account
                                       (cash-account-count-request
                                        :product-type-sub-ledger-term-deposit
                                        :account-type-personal
                                        "EUR" 11)))))
    (testing "while the set term still narrows the limit"
      (is (true? (SUT/check policies
                            :cash-account
                            (cash-account-count-request
                             :product-type-sub-ledger-current
                             :account-type-business
                             "GBP" 11)))))
    (testing "and a request with no product-type reaches no filter"
      (is (true? (SUT/check policies
                            :cash-account
                            {:aggregate :count
                             :window :time-window-instant
                             :value 11}))))))
