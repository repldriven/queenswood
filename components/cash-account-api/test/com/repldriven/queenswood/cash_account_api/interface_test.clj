(ns com.repldriven.queenswood.cash-account-api.interface-test
  "`->body` is the only thing standing between a stored cash account and
  a response body, so what it lets through is held to what `CashAccount`
  declares. `->wire-body` is that projection in the spelling a route
  sends it, which is what a surface outside the API base renders.

  The expected key set is read back out of the published registry rather
  than off the projection's own selection: a projection that selected
  from a hand-written list would diverge from the document and be
  caught here."
  (:require
    [com.repldriven.queenswood.cash-account-api.interface :as SUT]

    [clojure.test :refer [deftest is testing]]))

(def ^:private declared-keys
  (into []
        (comp (filter vector?) (map first))
        (get SUT/registry "CashAccount")))

(def ^:private stored-account
  "A cash account as the query brick hands it back: every key
  `CashAccount` declares, and the two idempotency keys the record also
  stores and the API never publishes."
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :account-id "acc.01kprbmgcj35ptc8npmybhh4s8"
   :party-id "pty.01kprbmgcj35ptc8npmybhh4s9"
   :name "Arthur Phillip Dent - Current Account"
   :currency "GBP"
   :product-id "prd.01kprbmgcj35ptc8npmybhh4se"
   :version-id "prv.01kprbmgcj35ptc8npmybhh4sf"
   :product-type :product-type-sub-ledger-current
   :account-type :account-type-personal
   :account-status :cash-account-status-opened
   :payment-addresses [{:scheme :payment-address-scheme-scan
                        :scan {:sort-code "040004" :account-number "12345678"}}]
   :retired-payment-addresses []
   :bban "04000412345678"
   :balances []
   :posted-balance {:value 8000 :currency "GBP"}
   :available-balance {:value 7700 :currency "GBP"}
   :transactions []
   :created-at 1700000000000
   :updated-at 1700000000001
   :idempotency-key "5b2f0f6e-open"
   :last-rotation-idempotency-key "5b2f0f6e-rotate"})

(deftest declared-keys-cover-the-fixture-test
  (testing
    "the fixture carries every key CashAccount declares, so a
           dropped key is a failure rather than a silent pass"
    (is (= (set declared-keys)
           (set (filter (set declared-keys) (keys stored-account)))))))

(deftest ->body-publishes-no-undeclared-key-test
  (let [body (SUT/->body stored-account)]
    (testing "the two stored idempotency keys do not reach a body"
      (is (not (contains? body :idempotency-key)))
      (is (not (contains? body :last-rotation-idempotency-key))))
    (testing "nor does any other key CashAccount does not declare"
      (is (empty? (remove (set declared-keys) (keys body)))))))

(deftest ->body-keeps-every-declared-key-test
  (let [body (SUT/->body stored-account)]
    (testing
      "every declared key present on the record survives, value
             and all — the BBAN among them"
      (is (= (select-keys stored-account declared-keys) body)))
    (is (= "04000412345678" (:bban body)))))

(deftest ->body-omits-a-declared-key-the-record-lacks-test
  (testing
    "an optional key absent from the record stays absent rather
           than arriving nil"
    (let [body (SUT/->body (dissoc stored-account :bban))]
      (is (not (contains? body :bban))))))

(deftest ->wire-body-spells-the-record-as-the-route-does-test
  (let [body (SUT/->wire-body stored-account)]
    (testing "every enum reaches the wire as the string CashAccount admits"
      (is (= "opened" (name (:account-status body))))
      (is (= "personal" (name (:account-type body))))
      (is (= "current" (name (:product-type body))))
      (is (= "scan" (name (:scheme (first (:payment-addresses body)))))))
    (testing "and every timestamp as ISO-8601"
      (is (= "2023-11-14T22:13:20Z" (:created-at body)))
      (is (= "2023-11-14T22:13:20.001Z" (:updated-at body))))
    (testing "the two stored idempotency keys still do not reach a body"
      (is (not (contains? body :idempotency-key)))
      (is (not (contains? body :last-rotation-idempotency-key))))
    (testing "and the embedded collections are not carried"
      (is (not (contains? body :balances)))
      (is (not (contains? body :transactions))))))
