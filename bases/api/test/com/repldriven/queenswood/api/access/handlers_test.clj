(ns ^:eftest/synchronized com.repldriven.queenswood.api.access.handlers-test
  "The actor a people write records is derived from the principal
  (REQ-028): an operator — a principal carrying `admin` — by its id, and
  anyone else as a member with the role of the membership the call
  resolved to. A recipient's proof is the `Invitation-Token` header's
  hash, never the token, and the token's email with its `email_verified`
  claim, and `GET /v1/me/invitations` lists nothing for an email that
  claim does not verify.

  The command send and its Avro coding, and the `member-query`,
  `user` and `bank-query` components, are stood in for, so no system is
  booted. A stand-in answers only on the test's own thread: a scenario
  running beside this namespace reaches the real function."
  (:require
    [com.repldriven.queenswood.api.access.handlers :as SUT]
    [com.repldriven.queenswood.api.auth :as auth]

    [com.repldriven.queenswood.bank-query.interface :as banks]
    [com.repldriven.queenswood.member-query.interface :as memberships]
    [com.repldriven.queenswood.user.interface :as users]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.command.interface :as command]
    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7")

(def ^:private user-id "usr.01kprbmgcj35ptc8npmybhh4s7")

(def ^:private invitation-id "inv.01kprbmgcj35ptc8npmybhh4sm")

(def ^:private membership-id "mem.01kprbpdwa9q5n2t7vwsx84a3m")

(def ^:private member-auth
  {:principal-type :user
   :principal-id user-id
   :bank-id bank-id
   :membership {:member-id membership-id :bank-id bank-id :role :role-admin}
   :roles #{:user auth/org-viewer auth/org-developer auth/org-admin}})

(def ^:private operator-auth
  {:principal-type :service
   :principal-id "queenswood-admin"
   :bank-id bank-id
   :roles (into #{:admin} auth/org-levels)})

(def ^:private subject-id "usr.01kprbmgcj35ptc8npmybhh4sp")

(def ^:private people
  {user-id {:user-id user-id :name "Ada Lovelace" :email "ada@example.com"}
   subject-id
   {:user-id subject-id :name "Charles Babbage" :email "charles@example.com"}})

(defn- find-person
  [_ id]
  (or (get people id)
      (error/reject :user/not-found {:message "User not found" :user-id id})))

(def ^:private ^:dynamic *stand-ins* {})

(defn- standing-in
  "Call `f` with each var in `stand-ins` answered by its stand-in on this
  thread, and by its own function on every other."
  [stand-ins f]
  (with-redefs-fn (into {}
                        (map (fn [[v _]]
                               (let [real @v]
                                 [v
                                  (fn [& args]
                                    (apply (get *stand-ins* v real) args))])))
                        stand-ins)
    (fn [] (binding [*stand-ins* stand-ins] (f)))))

(def ^:private change
  {:bank-id bank-id :member-id membership-id :invitation-id invitation-id})

(defn- commanding
  "Stand-ins answering every command with `reply`, called with the command
  name and the payload the handler sent, and recording each send in
  `sent`. Avro coding passes the data through unchanged."
  [sent reply]
  {#'avro/serialize (fn [_ data] data)
   #'avro/deserialize-same (fn [_ payload] payload)
   #'command/send (fn [_ envelope _]
                    (let [{:keys [command payload]} envelope]
                      (swap! sent conj [command payload])
                      (reply command payload)))})

(defn- accepted
  [_ _]
  {:status "ACCEPTED" :payload change})

(def ^:private schemas
  (zipmap ["invite" "resend-invitation" "withdraw-invitation"
           "accept-invitation" "decline-invitation" "change-role"
           "remove-member" "leave-bank" "access-change"]
          (repeat ::schema)))

(defn- with-avro
  [request]
  (assoc request :avro schemas :dispatchers {:members ::dispatcher}))

(defn- invitation
  [actor]
  {:invitation-id invitation-id
   :bank-id bank-id
   :email "c.babbage@example.com"
   :role :role-developer
   :status :invitation-status-pending
   :expires-at 1779955200000
   :created-at 1779350400000
   :created-by actor
   :updated-at 1779350400000})

(defn- invite-as
  [auth]
  (let [sent (atom [])
        actor (if (contains? (:roles auth) :admin)
                {:kind :actor-kind-operator :principal-id "queenswood-admin"}
                {:kind :actor-kind-member :principal-id user-id})]
    (standing-in (merge (commanding sent accepted)
                        {#'users/find-by-id find-person
                         #'memberships/find-invitation
                         (fn [& _] (invitation actor))})
                 (fn []
                   (let [response (SUT/invite
                                   (with-avro
                                    {:auth auth
                                     :parameters
                                     {:body {:email "c.babbage@example.com"
                                             :role :role-developer
                                             :reason "A reason"}}}))]
                     {:response response :sent @sent})))))

(deftest a-people-write-records-the-principal-as-actor-test
  (testing "a member acts with the role the call resolved to"
    (let [{:keys [response sent]} (invite-as member-auth)
          [[command data]] sent]
      (is (= 201 (:status response)))
      (is (= "invite" command))
      (is (= {:kind :actor-kind-member :principal-id user-id :role :role-admin}
             (:actor data)))
      (is (= {:bank-id bank-id
              :email "c.babbage@example.com"
              :role :role-developer
              :reason "A reason"}
             (dissoc data :actor)))))
  (testing "a principal carrying admin acts as the operator"
    (let [{:keys [response sent]} (invite-as operator-auth)
          [[_ data]] sent]
      (is (= 201 (:status response)))
      (is (= {:kind :actor-kind-operator :principal-id "queenswood-admin"}
             (:actor data)))))
  (testing "the response is the invitation the reply names, its actor named"
    (let [{:keys [response]} (invite-as member-auth)]
      (is (= invitation-id (get-in response [:body :invitation-id])))
      (is (=
           {:kind :actor-kind-member :principal-id user-id :name "Ada Lovelace"}
           (get-in response [:body :invited-by]))))))

(deftest a-recipient-proves-by-token-hash-or-verified-email-test
  (let [seen (atom nil)
        sent (atom [])
        {:keys [token token-hash]} (memberships/new-invitation-token)
        claims {:email "Charles@Example.com" :email_verified true}]
    (standing-in
     (merge (commanding sent accepted)
            {#'banks/get-bank (fn [_ _] {:name "Ada's Bank"})
             #'users/find-by-id find-person
             #'memberships/find-by-id
             (fn [& _] {:member-id membership-id :bank-id bank-id})
             #'memberships/find-invitation-for-recipient
             (fn [_ _ proof]
               (reset! seen proof)
               (invitation {:kind :actor-kind-member :principal-id user-id}))})
     (fn []
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
       (is
        (= {:token-hash nil :email "Charles@Example.com" :email-verified? false}
           @seen))
       (testing "an accept sends the token's hash and never the token"
         (SUT/accept-my-invitation
          (with-avro {:auth {:principal-id user-id :claims claims}
                      :headers {"invitation-token" token}
                      :parameters {:path {:invitation-id invitation-id}}}))
         (let [[[command data]] @sent]
           (is (= "accept-invitation" command))
           (is (= {:invitation-id invitation-id
                   :user-id user-id
                   :proof {:token-hash token-hash
                           :email "Charles@Example.com"
                           :email-verified true}}
                  data))
           (is (not (some #{token} (tree-seq coll? seq data))))))))))

(deftest my-invitations-need-a-verified-email-test
  (let [called (atom false)]
    (standing-in {#'memberships/list-pending-invitations-by-email
                  (fn [& _] (reset! called true) [])}
                 (fn []
                   (is (= {:status 200 :body {:items []}}
                          (SUT/list-my-invitations
                           {:auth {:claims {:email "a@example.com"
                                            :email_verified false}}})))
                   (is (false? @called)
                       "an unverified email is not looked up")))))
