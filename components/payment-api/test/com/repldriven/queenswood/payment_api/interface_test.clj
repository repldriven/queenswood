(ns com.repldriven.queenswood.payment-api.interface-test
  "`->outbound-body` and `->inbound-body` are what stand between a
  stored payment and a response body, so what each lets through is
  held to what its component declares, and the wire projections to the
  spelling a route sends. The expected key sets are read back out of
  the published registry rather than off the projections' own
  selections."
  (:require
    [com.repldriven.queenswood.payment-api.interface :as SUT]

    [clojure.test :refer [deftest is testing]]))

(defn- declared-keys
  [component]
  (into [] (comp (filter vector?) (map first)) (get SUT/registry component)))

(def ^:private stored-outbound
  "An outbound payment as the query brick hands it back: every key
  `OutboundPayment` declares, and the idempotency key the record also
  stores and the API never publishes."
  {:payment-id "pmt.01kprbmgcj35ptc8npmybhh4s5"
   :bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :scheme :payment-scheme-fps
   :debtor-account-id "acc.01kprbmgcj35ptc8npmybhh4s8"
   :creditor-bban "04000412345678"
   :creditor-name "Arthur Dent"
   :currency "GBP"
   :amount 2500
   :payment-status :outbound-payment-status-completed
   :transaction-id "txn.01kprbmgcj35ptc8npmybhh4s9"
   :reference "Towel"
   :cancellation-code nil
   :cancellation-reason nil
   :business-day "2023-11-14"
   :created-at 1700000000000
   :updated-at 1700000000001
   :idempotency-key "5b2f0f6e-outbound"})

(def ^:private stored-inbound
  {:payment-id "pmt.01kprbmgcj35ptc8npmybhh4sa"
   :bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :scheme "fps"
   :scheme-transaction-id "cb-txn-1"
   :end-to-end-id "e2e-1"
   :creditor-account-id "acc.01kprbmgcj35ptc8npmybhh4s8"
   :currency "GBP"
   :amount 100000
   :payment-status :inbound-payment-status-settled
   :transaction-id "txn.01kprbmgcj35ptc8npmybhh4sb"
   :debtor-name "Ford Prefect"
   :reference "Lunch"
   :business-day "2023-11-14"
   :created-at 1700000000000
   :updated-at 1700000000001
   :scheme-payload "{}"})

(deftest declared-keys-cover-the-fixtures-test
  (testing "each fixture carries every key its component declares"
    (is (= (set (declared-keys "OutboundPayment"))
           (set (filter (set (declared-keys "OutboundPayment"))
                        (keys stored-outbound)))))
    (is (= (set (declared-keys "InboundPayment"))
           (set (filter (set (declared-keys "InboundPayment"))
                        (keys stored-inbound)))))))

(deftest ->body-publishes-no-undeclared-key-test
  (testing "the stored idempotency key does not reach an outbound body"
    (let [body (SUT/->outbound-body stored-outbound)]
      (is (not (contains? body :idempotency-key)))
      (is (empty? (remove (set (declared-keys "OutboundPayment"))
                          (keys body))))))
  (testing "nor a scheme payload an inbound body"
    (let [body (SUT/->inbound-body stored-inbound)]
      (is (not (contains? body :scheme-payload)))
      (is (empty? (remove (set (declared-keys "InboundPayment"))
                          (keys body)))))))

(deftest ->body-keeps-every-declared-key-test
  (is (= (select-keys stored-outbound (declared-keys "OutboundPayment"))
         (SUT/->outbound-body stored-outbound)))
  (is (= (select-keys stored-inbound (declared-keys "InboundPayment"))
         (SUT/->inbound-body stored-inbound))))

(deftest ->wire-body-spells-the-record-as-the-route-does-test
  (let [outbound (SUT/->outbound-wire-body stored-outbound)
        inbound (SUT/->inbound-wire-body stored-inbound)]
    (testing "every enum reaches the wire as the string the document admits"
      (is (= "completed" (name (:payment-status outbound))))
      (is (= "fps" (name (:scheme outbound))))
      (is (= "settled" (name (:payment-status inbound)))))
    (testing "and every timestamp as ISO-8601"
      (is (= "2023-11-14T22:13:20Z" (:created-at outbound)))
      (is (= "2023-11-14T22:13:20.001Z" (:updated-at inbound))))
    (testing "the stored idempotency key still does not reach a body"
      (is (not (contains? outbound :idempotency-key))))))
