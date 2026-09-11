(ns com.repldriven.queenswood.api.cash-account.queries-test
  "A stored cash account carries fields the API never publishes — the
  key that opened it and the key that last rotated its addresses. The
  read routes project onto the keys `CashAccount` declares, so those
  two cannot reach a body while the BBAN, which is declared, does.

  The query brick is redefined here rather than started, because
  `get-account` and `get-accounts` are the whole boundary these
  handlers have: no FoundationDB is touched, so the namespace needs no
  system and no `^:eftest/synchronized`."
  (:require
    [com.repldriven.queenswood.api.cash-account.queries :as SUT]

    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7")
(def ^:private account-id "acc.01kprbmgcj35ptc8npmybhh4s8")

(def ^:private stored-account
  "A cash account as the query brick hands it back: the record's own
  fields, the two idempotency keys included."
  {:bank-id bank-id
   :account-id account-id
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
   :created-at 1700000000000
   :updated-at 1700000000001
   :idempotency-key "5b2f0f6e-open"
   :last-rotation-idempotency-key "5b2f0f6e-rotate"})

(defn- request
  []
  {:auth {:bank-id bank-id}
   :parameters {:path {:account-id account-id} :query {}}})

(deftest get-cash-account-publishes-only-declared-keys-test
  (with-redefs [cash-accounts/get-account (fn [& _] stored-account)]
    (let [{:keys [status body]} (SUT/get-cash-account (request))]
      (is (= 200 status))
      (testing "the BBAN travels" (is (= "04000412345678" (:bban body))))
      (testing "the idempotency keys do not"
        (is (not (contains? body :idempotency-key)))
        (is (not (contains? body :last-rotation-idempotency-key)))))))

(deftest list-cash-accounts-publishes-only-declared-keys-test
  (with-redefs [cash-accounts/get-accounts
                (fn [& _] {:accounts [stored-account] :before nil :after nil})]
    (let [{:keys [status body]} (SUT/list-cash-accounts (request))
          [account] (:cash-accounts body)]
      (is (= 200 status))
      (is (= "04000412345678" (:bban account)))
      (is (not (contains? account :idempotency-key)))
      (is (not (contains? account :last-rotation-idempotency-key))))))

(deftest get-cash-account-answers-a-missing-account-from-the-rejection-test
  ;; The handler has no not-found branch of its own — `get-account`
  ;; rejects rather than returning nil, and the 404 comes off the kind.
  (with-redefs [cash-accounts/get-account (fn [& _]
                                            (error/reject
                                             :cash-account/not-found
                                             {:message "Account not found"
                                              :bank-id bank-id
                                              :account-id account-id}))]
    (let [{:keys [status body]} (SUT/get-cash-account (request))]
      (is (= 404 status))
      (is (= 404 (:status body)))
      (is (= "REJECTED" (:title body)))
      (is (= ":cash-account/not-found" (:type body)))
      (is (= "Account not found" (:detail body))))))
