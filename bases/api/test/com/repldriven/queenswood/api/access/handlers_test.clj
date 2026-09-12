(ns ^:eftest/synchronized com.repldriven.queenswood.api.access.handlers-test
  "The actor a people write records is derived from the principal
  (REQ-028): an operator — a principal carrying `admin` — by its id, and
  anyone else as a member with the role of the membership the call
  resolved to. A recipient's proof is the `Invitation-Token` header's
  hash and the token's email with its `email_verified` claim, and
  `GET /v1/me/invitations` lists nothing for an email that claim does not
  verify.

  The `membership` and `bank-query` components are stood in for with
  `with-redefs`, so no system is booted, and the vars run one at a time."
  (:require
    [com.repldriven.queenswood.api.access.handlers :as SUT]

    [com.repldriven.queenswood.api.auth :as auth]

    [com.repldriven.queenswood.bank-query.interface :as banks]
    [com.repldriven.queenswood.membership.interface :as memberships]

    [clojure.test :refer [deftest is testing]]))

(def ^:private bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7")

(def ^:private user-id "usr.01kprbmgcj35ptc8npmybhh4s7")

(def ^:private invitation-id "inv.01kprbmgcj35ptc8npmybhh4sm")

(def ^:private member-auth
  {:principal-type :user
   :principal-id user-id
   :bank-id bank-id
   :membership {:membership-id "mem.01kprbpdwa9q5n2t7vwsx84a3m"
                :bank-id bank-id
                :role :role-admin}
   :roles #{:user auth/org-viewer auth/org-developer auth/org-admin}})

(def ^:private operator-auth
  {:principal-type :service
   :principal-id "queenswood-admin"
   :bank-id bank-id
   :roles (into #{:admin} auth/org-levels)})

(defn- invitation
  [opts]
  {:invitation-id invitation-id
   :bank-id bank-id
   :email "c.babbage@example.com"
   :role :role-developer
   :status :invitation-status-pending
   :expires-at 1779955200000
   :invited-by (:actor opts)
   :created-at 1779350400000
   :updated-at 1779350400000})

(defn- invite-as
  [auth]
  (let [seen (atom nil)]
    (with-redefs [memberships/invite (fn [_ _ _ opts]
                                       (reset! seen opts)
                                       (invitation opts))]
      (let [response (SUT/invite {:auth auth
                                  :parameters {:body {:email
                                                      "c.babbage@example.com"
                                                      :role :role-developer
                                                      :reason "A reason"}}})]
        {:response response :opts @seen}))))

(deftest a-people-write-records-the-principal-as-actor-test
  (testing "a member acts with the role the call resolved to"
    (let [{:keys [response opts]} (invite-as member-auth)]
      (is (= 201 (:status response)))
      (is (= {:kind :actor-kind-member :principal-id user-id :role :role-admin}
             (:actor opts)))
      (is (= "A reason" (:reason opts)))))
  (testing "a principal carrying admin acts as the operator"
    (let [{:keys [response opts]} (invite-as operator-auth)]
      (is (= 201 (:status response)))
      (is (= {:kind :actor-kind-operator :principal-id "queenswood-admin"}
             (:actor opts)))))
  (testing
    "the response carries the invitation, its actor, and the token
            whose hash was stored"
    (let [{:keys [response opts]} (invite-as member-auth)
          {:keys [token]} (:body response)]
      (is (string? token))
      (is (= (:token-hash opts) (memberships/token-hash token)))
      (is (= {:kind :actor-kind-member :principal-id user-id}
             (get-in response [:body :invitation :invited-by]))))))

(deftest a-recipient-proves-by-token-or-verified-email-test
  (let [seen (atom nil)
        {:keys [token token-hash]} (memberships/new-invitation-token)
        claims {:email "Charles@Example.com" :email_verified true}]
    (with-redefs [banks/get-bank (fn [_ _] {:name "Ada's Bank"})
                  memberships/find-invitation-for-recipient
                  (fn [_ _ proof]
                    (reset! seen proof)
                    (invitation {:actor {:kind :actor-kind-member
                                         :principal-id user-id}}))]
      (SUT/get-my-invitation {:auth {:claims claims}
                              :headers {"invitation-token" token}
                              :parameters {:path {:invitation-id
                                                  invitation-id}}})
      (is (= {:token-hash token-hash
              :email "Charles@Example.com"
              :email-verified? true}
             @seen))
      (SUT/get-my-invitation {:auth {:claims (dissoc claims :email_verified)}
                              :parameters {:path {:invitation-id
                                                  invitation-id}}})
      (is (=
           {:token-hash nil :email "Charles@Example.com" :email-verified? false}
           @seen)))))

(deftest my-invitations-need-a-verified-email-test
  (let [called (atom false)]
    (with-redefs [memberships/list-pending-invitations-by-email
                  (fn [& _] (reset! called true) [])]
      (is (= {:status 200 :body {:items []}}
             (SUT/list-my-invitations {:auth {:claims {:email "a@example.com"
                                                       :email_verified
                                                       false}}})))
      (is (false? @called) "an unverified email is not looked up"))))
