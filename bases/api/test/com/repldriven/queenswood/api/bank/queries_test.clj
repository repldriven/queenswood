(ns com.repldriven.queenswood.api.bank.queries-test
  "Every bank `GET /v1/banks` lists carries `owners` (REQ-003): one entry
  per active membership of role owner, named from its user record, and
  empty when the bank has none. A failed read answers that anomaly's
  problem response rather than a bank with no owners.

  No system is booted, and no var is redefined: the bank list and the
  membership and user reads are the functions each test hands to
  `banks-response` and `names/owners`."
  (:require
    [com.repldriven.queenswood.api.bank.queries :as SUT]

    [com.repldriven.queenswood.api.access.names :as names]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private owned-bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7")

(def ^:private ownerless-bank-id "bnk.01kprbmgcj35ptc8npmybhh4s8")

(def ^:private ada "usr.01kprbmgcj35ptc8npmybhh4s7")

(def ^:private charles "usr.01kprbmgcj35ptc8npmybhh4sp")

(def ^:private unnamed "usr.01kprbmgcj35ptc8npmybhh4sq")

(def ^:private departed "usr.01kprbmgcj35ptc8npmybhh4sr")

(def ^:private people
  {ada {:user-id ada :name "Ada Lovelace" :email "ada@example.com"}
   charles {:user-id charles :name "Charles Babbage" :email "cb@example.com"}
   unnamed {:user-id unnamed :name "" :email "unnamed@example.com"}})

(defn- membership
  [membership-id bank-id user-id role]
  {:membership-id membership-id :bank-id bank-id :user-id user-id :role role})

(def ^:private active-memberships
  {owned-bank-id
   [(membership "mem.01kprbpdwa9q5n2t7vwsx84a3m" owned-bank-id ada :role-owner)
    (membership "mem.01kprbpdwa9q5n2t7vwsx84a3n"
                owned-bank-id
                charles
                :role-admin)
    (membership "mem.01kprbpdwa9q5n2t7vwsx84a3p"
                owned-bank-id
                unnamed
                :role-owner)
    (membership "mem.01kprbpdwa9q5n2t7vwsx84a3q"
                owned-bank-id
                departed
                :role-owner)]
   ownerless-bank-id [(membership "mem.01kprbpdwa9q5n2t7vwsx84a3r"
                                  ownerless-bank-id
                                  charles
                                  :role-developer)]})

(def ^:private listed-banks
  [{:bank-id owned-bank-id :name "Ada's Bank"}
   {:bank-id ownerless-bank-id :name "Charles's Bank"}])

(defn- find-person
  [id]
  (or (get people id)
      (error/reject :user/not-found {:message "User not found" :user-id id})))

(defn- active-memberships-of
  [bank-id]
  (get active-memberships bank-id []))

(defn- list-banks
  ([] (list-banks {}))
  ([reads]
   (let [{:keys [found list-active lookup]}
         (merge {:found {:banks listed-banks}
                 :list-active active-memberships-of
                 :lookup find-person}
                reads)]
     (SUT/banks-response nil
                         found
                         (fn [bank-id]
                           (names/owners list-active lookup bank-id))))))

(defn- listed-bank
  [response bank-id]
  (some (fn [bank] (when (= bank-id (:bank-id bank)) bank))
        (get-in response [:body :items])))

(deftest a-bank-lists-its-owners-only-test
  (let [response (list-banks)]
    (is (= 200 (:status response)))
    (testing "an owner is named from their user record, an admin is left out"
      (is (= [{:membership-id "mem.01kprbpdwa9q5n2t7vwsx84a3m"
               :user-id ada
               :name "Ada Lovelace"
               :email "ada@example.com"}
              {:membership-id "mem.01kprbpdwa9q5n2t7vwsx84a3p"
               :user-id unnamed
               :email "unnamed@example.com"}
              {:membership-id "mem.01kprbpdwa9q5n2t7vwsx84a3q"
               :user-id departed}]
             (:owners (listed-bank response owned-bank-id)))))))

(deftest a-bank-with-no-owner-lists-none-test
  (let [response (list-banks)
        ownerless (listed-bank response ownerless-bank-id)]
    (is (= 200 (:status response)))
    (is (contains? ownerless :owners))
    (is (= [] (:owners ownerless)))))

(deftest a-failed-read-answers-its-problem-response-test
  (testing "a membership read"
    (let [failure (error/fail :membership/read {:message "Store unavailable"})]
      (is (= {:status 500
              :body {:title "FAILED"
                     :type ":membership/read"
                     :status 500
                     :detail "Store unavailable"}}
             (list-banks {:list-active (fn [_] failure)})))))
  (testing "a user read other than not-found"
    (let [failure (error/fail :user/read {:message "Store unavailable"})
          response (list-banks {:lookup (fn [_] failure)})]
      (is (= 500 (:status response)))
      (is (= ":user/read" (get-in response [:body :type])))
      (is (not (contains? (:body response) :items)))))
  (testing "the bank list itself"
    (let [failure (error/fail :bank/read {:message "Store unavailable"})
          response (list-banks {:found failure})]
      (is (= 500 (:status response)))
      (is (= ":bank/read" (get-in response [:body :type]))))))
