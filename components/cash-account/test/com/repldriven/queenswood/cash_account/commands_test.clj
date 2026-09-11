(ns ^:eftest/synchronized com.repldriven.queenswood.cash-account.commands-test
  "The dispatch table: which fn each command name reaches, what an
  unknown name answers, and the envelope id arriving on the decoded
  data as `:idempotency-key`. The work each fn then does is
  `interface-test`'s.

  The probe fns replace root bindings the rest of the workspace also
  calls, so this namespace runs on its own."
  (:require
    [com.repldriven.queenswood.cash-account.commands :as SUT]
    [com.repldriven.queenswood.cash-account.core :as core]

    [com.repldriven.queenswood.cash-account-query.interface :as q]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.processor.interface :as processor]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]

    [clojure.test :refer [deftest is testing]]))

(def ^:private config-file
  "classpath:cash-account/application-commands-test.yml")

(def ^:private test-bank-id "bnk.commands.dispatch")
(def ^:private test-account-id "acc.commands.dispatch")

(defn- reached
  "Stands in for the fn a handler calls, answering with an anomaly
  naming itself. `->response` passes an anomaly straight back, so the
  probe reaches the caller without a serialisable account behind it."
  [name data]
  (error/fail :test/reached {:fn name :data data}))

(defn- probe
  [name]
  (fn [_config data] (reached name data)))

(defn- probe-get-account
  [_txn bank-id account-id]
  (reached "get-account" {:bank-id bank-id :account-id account-id}))

(def ^:private command->fn
  "Every name the dispatch table carries, against the fn it routes to."
  {"open-cash-account" "open-account"
   "close-cash-account" "close-account"
   "suspend-cash-account" "suspend-account"
   "resume-cash-account" "resume-account"
   "rotate-cash-account-address" "rotate-address"
   "get-cash-account" "get-account"})

(defn- command-data
  [command]
  (if (= "open-cash-account" command)
    {:bank-id test-bank-id
     :party-id "pty.commands.dispatch"
     :name "Dispatch Test Account"
     :currency "GBP"
     :product-id "prd.commands.dispatch"}
    {:bank-id test-bank-id :account-id test-account-id}))

(defn- message
  [schemas command id]
  {:command command
   :id id
   :payload (avro/serialize (schemas command) (command-data command))})

(defn- dispatch
  [schemas command id]
  (processor/process (SUT/->CashAccountProcessor {:schemas schemas})
                     (message schemas command id)))

(deftest each-command-name-reaches-its-own-fn-test
  (with-test-system
   [sys config-file]
   (let [schemas (system/instance sys [:avro :serde])]
     (with-redefs [core/open-account (probe "open-account")
                   core/close-account (probe "close-account")
                   core/suspend-account (probe "suspend-account")
                   core/resume-account (probe "resume-account")
                   core/rotate-address (probe "rotate-address")
                   q/get-account probe-get-account]
       (doseq [[command expected] command->fn]
         (testing command
           (let [result (dispatch schemas command "ik-dispatch-00000001")]
             (is (= :test/reached (error/kind result)))
             (is (= expected (:fn (error/payload result)))))))))))

(deftest the-envelope-id-becomes-the-idempotency-key-test
  (with-test-system
   [sys config-file]
   (let [schemas (system/instance sys [:avro :serde])
         id "ik-dispatch-00000002"]
     (with-redefs [core/open-account (probe "open-account")]
       (testing "the decoded data carries the envelope id as its key"
         (let [result (dispatch schemas "open-cash-account" id)]
           (is (= id (:idempotency-key (:data (error/payload result)))))
           (is (= test-bank-id (:bank-id (:data (error/payload result)))))))))))

(deftest an-unknown-command-name-is-rejected-test
  (with-test-system
   [sys config-file]
   (let [schemas (system/instance sys [:avro :serde])
         processor (SUT/->CashAccountProcessor {:schemas schemas})]
     (testing "a name the table does not carry is rejected, not dropped"
       (let [result (processor/process processor
                                       {:command "not-a-cash-account-command"
                                        :id "ik-dispatch-00000003"
                                        :payload (byte-array 0)})]
         (is (error/rejection? result))
         (is (= :cash-account/unknown-command (error/kind result))))))))
