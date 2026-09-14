(ns com.repldriven.queenswood.api.access.names-test
  "Every person an access response refers to is named (REQ-004): a user
  by their record's name, or their email when it has none, each distinct
  id looked up once; the platform where no record stands behind a
  principal id; and to a recipient, never by an email. No system is
  booted: `lookup` is the function each test hands in."
  (:require
    [com.repldriven.queenswood.api.access.names :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private ada "usr.01kprbmgcj35ptc8npmybhh4s7")

(def ^:private charles "usr.01kprbmgcj35ptc8npmybhh4sp")

(def ^:private unnamed "usr.01kprbmgcj35ptc8npmybhh4sq")

(def ^:private people
  {ada {:user-id ada :name "Ada Lovelace" :email "ada@example.com"}
   charles {:user-id charles :name "Charles Babbage" :email "cb@example.com"}
   unnamed {:user-id unnamed :name "" :email "unnamed@example.com"}})

(defn- counting-lookup
  "A lookup answering `people`, and `:user/not-found` for anyone else,
  beside an atom counting its calls by id."
  []
  (let [calls (atom {})]
    {:calls calls
     :lookup (fn [id]
               (swap! calls update id (fnil inc 0))
               (or (get people id)
                   (error/reject :user/not-found
                                 {:message "User not found" :user-id id})))}))

(deftest user-names-looks-each-distinct-id-up-once-test
  (let [{:keys [calls lookup]} (counting-lookup)
        names (SUT/user-names lookup [ada charles ada nil charles ada])]
    (is (= {ada "Ada Lovelace" charles "Charles Babbage"} names))
    (is (= {ada 1 charles 1} @calls))))

(deftest user-names-falls-back-to-the-email-test
  (let [{:keys [lookup]} (counting-lookup)]
    (is (= {unnamed "unnamed@example.com"} (SUT/user-names lookup [unnamed])))))

(deftest user-names-leaves-out-a-missing-user-test
  (let [{:keys [lookup]} (counting-lookup)]
    (testing "a user with no record is left out"
      (is (= {ada "Ada Lovelace"}
             (SUT/user-names lookup [ada "queenswood-admin"]))))
    (testing "any other anomaly is returned"
      (let [failure (error/fail :user/read {:message "Store unavailable"})
            result (SUT/user-names (fn [id]
                                     (if (= id charles) failure (lookup id)))
                                   [ada charles])]
        (is (error/anomaly? result))
        (is (= :user/read (error/kind result)))))))

(deftest recipient-names-never-answer-an-email-test
  (let [{:keys [lookup]} (counting-lookup)
        names (SUT/recipient-names lookup [ada unnamed])]
    (is (not-any? (set (map :email (vals people))) (vals names)))
    (testing "an inviter with no name is named by what the recipient is given"
      (is (= "Ada's Bank"
             (:name (SUT/->actor {:kind :actor-kind-member
                                  :principal-id unnamed}
                                 names
                                 "Ada's Bank")))))
    (testing "a named inviter keeps their name"
      (is (= "Ada Lovelace"
             (:name (SUT/->actor {:kind :actor-kind-member :principal-id ada}
                                 names
                                 "Ada's Bank")))))))

(deftest actor-names-the-platform-client-as-the-platform-test
  (let [{:keys [lookup]} (counting-lookup)
        actor {:kind :actor-kind-operator :principal-id "queenswood-admin"}
        names (SUT/user-names lookup (SUT/actor-ids [actor]))]
    (is (= {:kind :actor-kind-operator
            :principal-id "queenswood-admin"
            :name SUT/platform-name}
           (SUT/->actor actor names)))
    (is (= "Queenswood" SUT/platform-name))))

(deftest actor-answers-only-kind-principal-id-and-name-test
  (is (= {:kind :actor-kind-member :principal-id ada :name "Ada Lovelace"}
         (SUT/->actor
          {:kind :actor-kind-member :principal-id ada :role :role-admin}
          {ada "Ada Lovelace"}))))
