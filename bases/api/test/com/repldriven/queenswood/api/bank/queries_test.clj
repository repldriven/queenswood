(ns com.repldriven.queenswood.api.bank.queries-test
  "Every bank `GET /v1/banks` lists carries `owners` (REQ-003): one entry
  per active member of role owner, named from its user record, and
  empty when the bank has none. A failed read answers that anomaly's
  problem response rather than a bank with no owners.

  No system is booted, and no var is redefined: the bank list and the
  member and user reads are the functions each test hands to
  `banks-response` and `names/owners`. A bank shows the provider of each
  kind offered it records, and the default where it records none."
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

(defn- member
  [member-id bank-id user-id role]
  {:member-id member-id :bank-id bank-id :user-id user-id :role role})

(def ^:private active-members
  {owned-bank-id
   [(member "mem.01kprbpdwa9q5n2t7vwsx84a3m" owned-bank-id ada :role-owner)
    (member "mem.01kprbpdwa9q5n2t7vwsx84a3n" owned-bank-id charles :role-admin)
    (member "mem.01kprbpdwa9q5n2t7vwsx84a3p" owned-bank-id unnamed :role-owner)
    (member "mem.01kprbpdwa9q5n2t7vwsx84a3q"
            owned-bank-id
            departed
            :role-owner)]
   ownerless-bank-id [(member "mem.01kprbpdwa9q5n2t7vwsx84a3r"
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

(defn- active-members-of
  [bank-id]
  (get active-members bank-id []))

(defn- list-banks
  ([] (list-banks {}))
  ([reads]
   (let [{:keys [found list-active lookup]}
         (merge {:found {:banks listed-banks}
                 :list-active active-members-of
                 :lookup find-person}
                reads)]
     (SUT/banks-response {}
                         nil
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
      (is (= [{:member-id "mem.01kprbpdwa9q5n2t7vwsx84a3m"
               :user-id ada
               :name "Ada Lovelace"
               :email "ada@example.com"}
              {:member-id "mem.01kprbpdwa9q5n2t7vwsx84a3p"
               :user-id unnamed
               :email "unnamed@example.com"}
              {:member-id "mem.01kprbpdwa9q5n2t7vwsx84a3q" :user-id departed}]
             (:owners (listed-bank response owned-bank-id)))))))

(deftest a-bank-with-no-owner-lists-none-test
  (let [response (list-banks)
        ownerless (listed-bank response ownerless-bank-id)]
    (is (= 200 (:status response)))
    (is (contains? ownerless :owners))
    (is (= [] (:owners ownerless)))))

(deftest a-failed-read-answers-its-problem-response-test
  (testing "a member read"
    (let [failure (error/fail :member/read {:message "Store unavailable"})]
      (is (= {:status 500
              :body {:title "FAILED"
                     :type ":member/read"
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

(def ^:private offering
  {:providers
   {:payment {:kind "payment" :default :rails :providers {:rails {} :pooled {}}}
    :idv {:kind "idv" :default :verifier :providers {:verifier {}}}}})

(deftest a-bank-shows-its-providers-test
  (testing "the provider it records, and the default of a kind it does not"
    (is (= {:payment "pooled" :idv "verifier"}
           (:providers (SUT/bank-body offering
                                      {:providers [{:kind "payment"
                                                    :provider "pooled"}]})))))
  (testing "the default of every kind for a bank recording none"
    (is (= {:payment "rails" :idv "verifier"}
           (:providers (SUT/bank-body offering {:providers []}))))))

(deftest the-providers-offered-are-listed-test
  (is (= [{:kind "idv" :providers ["verifier"] :default "verifier"}
          {:kind "payment" :providers ["pooled" "rails"] :default "rails"}]
         (get-in (SUT/list-providers offering) [:body :items]))))
