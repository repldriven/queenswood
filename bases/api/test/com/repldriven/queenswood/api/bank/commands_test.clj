(ns com.repldriven.queenswood.api.bank.commands-test
  "A bank whose credential rotation answers no secret must not reach
  the caller as a 201 carrying nothing usable, and an operator's create
  sends the owner invitation's address and the operator as actor, and no
  token (REQ-028, OQ-2). An operator may leave status, tier and
  currencies out, and a person may name none of them.

  The identity provider here is a `reify` over the protocol's own
  `-rotate-secret`, and the request carries no store at all. That the
  guard's anomaly comes back rather than a failure from the view read
  is the evidence that `let-nom>` short-circuits before it — no
  FoundationDB is touched, so the namespace needs no system."
  (:require
    [com.repldriven.queenswood.api.bank.commands :as SUT]

    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.identity-provider.protocol :as protocol]

    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]))

(def ^:private bank-id "bnk.x")

(defn- request
  "A request whose provider rotates to `secret`, and that carries
  neither `:record-db` nor `:record-store`."
  [secret]
  {:identity-provider
   #_{:clj-kondo/ignore [:missing-protocol-method]}
   (reify
    protocol/IdentityProvider
      (-rotate-secret [_ id]
        {:client-id id :client-secret secret}))})

(deftest bank-with-secret-refuses-a-missing-credential-test
  (doseq [[label secret] [["a nil secret" nil] ["a blank secret" ""]]]
    (testing label
      (let [result (SUT/bank-with-secret (request secret) bank-id)]
        (is (error/anomaly? result))
        (is (error/error? result)
            "an error, not a rejection: no retry and no caller fix")
        (is (= :bank/credential-not-issued (error/kind result)))
        (is (= bank-id (:bank-id (error/payload result))))
        (testing "answers the caller a 5xx problem naming the bank"
          (let [response (errors/anomaly->response result)]
            (is (= 500 (:status response)))
            (is (= 500 (get-in response [:body :status])))
            (is (= "FAILED" (get-in response [:body :title])))
            (is (= ":bank/credential-not-issued"
                   (get-in response [:body :type])))
            (is (str/includes? (get-in response [:body :detail]) bank-id))))))))

(defn- create-request
  [body]
  {:auth {:principal-type :service
          :principal-id "queenswood-admin"
          :roles #{:admin}}
   :audiences-by-status {:bank-status-test "queenswood-test"}
   :parameters {:body (merge {:name "Galactic Bank"
                              :status :bank-status-test
                              :tier "micro"
                              :currencies ["GBP"]}
                             body)}})

(deftest an-operator-create-sends-the-owner-address-test
  (let [data (SUT/create-bank-data (create-request {:owner-email
                                                    "zaphod@example.com"})
                                   nil)]
    (testing "the owner invitation carries the address alone"
      (is (= {:email "zaphod@example.com"} (:owner-invitation data))))
    (testing "and the command carries no address field"
      (is (not (contains? data :owner-email))))
    (testing "the operator is the actor, by principal id"
      (is (= {:kind :actor-kind-operator :principal-id "queenswood-admin"}
             (:actor data))))
    (testing "the status's audience is resolved"
      (is (= "queenswood-test" (:audience data))))))

(deftest an-operator-create-without-an-owner-email-invites-nobody-test
  (let [data (SUT/create-bank-data (create-request {}) nil)]
    (is (not (contains? data :owner-invitation)))
    (is (= {:kind :actor-kind-operator :principal-id "queenswood-admin"}
           (:actor data)))))

(deftest an-operator-create-defaults-what-it-leaves-out-test
  (let [data (SUT/create-bank-data {:auth {:principal-type :service
                                           :principal-id "queenswood-admin"
                                           :roles #{:admin}}
                                    :audiences-by-status {:bank-status-test
                                                          "queenswood-test"}
                                    :parameters {:body {:name "Galactic Bank"}}}
                                   nil)]
    (is (= {:status :bank-status-test :tier "micro" :currencies ["GBP"]}
           (select-keys data [:status :tier :currencies])))
    (is (= "queenswood-test" (:audience data)))
    (is (not (contains? data :membership)) "an operator is not the owner")))

(defn- person-request
  [body]
  {:auth {:principal-type :user :principal-id "usr.x" :roles #{:user}}
   :parameters {:body (merge {:name "Galactic Bank"} body)}})

(deftest a-person-names-no-operator-field-test
  (doseq [field [{:status :bank-status-live} {:tier "enterprise"}
                 {:currencies ["EUR"]} {:owner-email "zaphod@example.com"}]]
    (testing (str (key (first field)))
      (let [{:keys [status body]} (SUT/create-bank
                                   (person-request
                                    (assoc field :company-number "SC998137")))]
        (is (= 403 status))
        (is (= "auth/forbidden" (:type body)))))))

(deftest a-person-names-a-company-test
  (let [{:keys [status body]} (SUT/create-bank (person-request {}))]
    (is (= 422 status))
    (is (= ":bank/company-required" (:type body)))))
