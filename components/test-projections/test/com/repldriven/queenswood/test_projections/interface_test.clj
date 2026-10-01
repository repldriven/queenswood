(ns com.repldriven.queenswood.test-projections.interface-test
  "The model side of each projection pair, over a hand-built model
  state. The real side reads production records and is compared with
  these by the domain scenarios."
  (:require
    [com.repldriven.queenswood.test-projections.interface :as SUT]

    [com.repldriven.queenswood.test-model.interface :as model]

    [clojure.test :refer [deftest is testing]]))

(def ^:private state
  (-> model/init-state
      (assoc :banks
             {:bank-0 {:accounts [:acct-0 :acct-1]
                       :products [:prod-0]
                       :parties [:party-0 :party-1]}})
      (assoc :products
             {:prod-0 {:bank :bank-0
                       :product-type :current
                       :versions [{:status :published :number 1}
                                  {:status :draft :number 2}]}})
      (assoc :parties
             {:party-0 {:bank :bank-0 :status :active}
              :party-1 {:bank :bank-0 :status :active}})
      (assoc :accounts
             {:acct-0 {:available 100
                       :interest-accrued 3
                       :credit-carry 250000
                       :transaction-legs 4
                       :status :open
                       :bank :bank-0
                       :product :prod-0
                       :party :party-0}
              :acct-1 {:available -50
                       :status :closed
                       :bank :bank-0
                       :product :prod-0
                       :party :party-1}})
      (assoc :payments {:pmt-0 {:debtor :acct-0 :amount 5 :status :completed}})
      (assoc :inbound-payments #{:in-0 :in-1})))

(deftest project-model-balances-test
  (testing "reads :available off each model account"
    (is (= {:acct-0 100 :acct-1 -50} (SUT/project-model-balances state))))
  (testing "empty state projects to empty map"
    (is (= {} (SUT/project-model-balances model/init-state)))))

(deftest project-model-products-test
  (testing "each product's versions, newest first"
    (is (= {:prod-0 [{:status :draft :number 2} {:status :published :number 1}]}
           (SUT/project-model-products state)))))

(deftest project-model-parties-test
  (is (= {:party-0 :active :party-1 :active}
         (SUT/project-model-parties state))))

(deftest project-model-banks-test
  (is (= {:bank-0 {:accounts #{:acct-0 :acct-1}
                   :products #{:prod-0}
                   :parties #{:party-0 :party-1}}}
         (SUT/project-model-banks state))))

(deftest project-model-accounts-test
  (is (= {:acct-0 {:bank :bank-0 :product :prod-0 :party :party-0 :status :open}
          :acct-1
          {:bank :bank-0 :product :prod-0 :party :party-1 :status :closed}}
         (SUT/project-model-accounts state))))

(deftest project-model-transactions-test
  (testing "an account with no legs counts zero"
    (is (= {:acct-0 4 :acct-1 0} (SUT/project-model-transactions state)))))

(deftest project-model-payments-test
  (is (= {:pmt-0 :completed} (SUT/project-model-outbound-payments state)))
  (is (= #{:in-0 :in-1} (SUT/project-model-inbound-payments state))))

(deftest project-model-interest-test
  (testing "an account that never accrued projects zeros"
    (is (= {:acct-0 {:interest-accrued 3 :credit-carry 250000}
            :acct-1 {:interest-accrued 0 :credit-carry 0}}
           (SUT/project-model-interest state)))))
