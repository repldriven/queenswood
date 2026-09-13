(ns com.repldriven.queenswood.api.bank.commands-test
  "A bank whose credential rotation answers no secret must not reach
  the caller as a 201 carrying nothing usable, and an operator's create
  sends the owner invitation's token hash and the operator as actor,
  never the plaintext token (REQ-028, OQ-2).

  The identity provider here is a `reify` over the protocol's own
  `-rotate-secret`, and the request carries no store at all. That the
  guard's anomaly comes back rather than a failure from the view read
  is the evidence that `let-nom>` short-circuits before it — no
  FoundationDB is touched, so the namespace needs no system."
  (:require
    [com.repldriven.queenswood.api.bank.commands :as SUT]

    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.membership.interface :as memberships]

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

(deftest an-operator-create-sends-the-token-hash-test
  (let [{:keys [token token-hash]} (memberships/new-invitation-token)
        data (SUT/create-bank-data (create-request {:owner-email
                                                    "zaphod@example.com"})
                                   token-hash)]
    (testing "the owner invitation carries the address and the hash"
      (is (= {:email "zaphod@example.com" :token-hash token-hash}
             (:owner-invitation data)))
      (is (= token-hash (memberships/token-hash token))))
    (testing "and the command carries neither the token nor the address field"
      (is (not (contains? data :owner-email)))
      (is (not-any? #{token} (tree-seq coll? seq data))))
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
