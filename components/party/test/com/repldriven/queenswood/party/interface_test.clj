(ns com.repldriven.queenswood.party.interface-test
  "The dispatcher's unknown-command rejection, and the one write path
  whose behaviour only a store can show: a create retried under the
  key that made the party gets that party back. Happy-path command
  handlers, watcher behaviour, and rejection paths surfaced by the API
  are covered by the EDN scenario suite (parties/*.edn in
  bank-test-api-scenarios) and the domain-level pure-function tests."
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.party.commands :as commands]
    [com.repldriven.queenswood.party.interface :as SUT]

    [com.repldriven.queenswood.party-query.interface :as q]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.test :refer [deftest is testing]]))

(deftest unknown-command-test
  (testing "dispatch rejects command names not in the handler registry"
    (let [result (#'commands/dispatch
                  {:schemas {}}
                  {:command "unknown-party-command" :payload nil})]
      (is (error/rejection? result))
      (is (= :party/unknown-command (error/kind result))))))

(def ^:private test-bank-id "bnk_party_retry_test")

(def ^:private allow-create
  [{:enabled true
    :capabilities [{:effect :effect-allow
                    :kind {:party {:action :party-action-create}}}]}])

(defn- organisation
  [idempotency-key]
  {:bank-id test-bank-id
   :type :party-type-organization
   :display-name "Retried Organisation"
   :idempotency-key idempotency-key})

(deftest new-party-retried-under-one-key-creates-one-party-test
  (with-test-system
   [sys "classpath:party/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}
         key "party-retry-key-0000000001"
         opts {:policies allow-create}]
     (nom-test> [first-party (SUT/new-party config (organisation key) opts)
                 retried (SUT/new-party config (organisation key) opts)
                 _ (testing
                     "the retry is answered with the party the first made"
                     (is (= (:party-id first-party) (:party-id retried)))
                     (is (= key (:idempotency-key retried))))
                 _ (testing "and the bank holds one party, not two"
                     (nom-test> [listed (q/get-parties config test-bank-id)
                                 _ (is (= 1 (count (:parties listed))))]))]))))
