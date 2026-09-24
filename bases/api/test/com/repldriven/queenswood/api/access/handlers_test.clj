(ns ^:eftest/synchronized com.repldriven.queenswood.api.access.handlers-test
  "The actor a people write records is derived from the principal
  (REQ-028): an operator — a principal carrying `admin` — by its id, and
  anyone else as a member with the role of the membership the call
  resolved to. A recipient's proof is the `Invitation-Token` header's
  hash, never the token, and the token's email with its `email_verified`
  claim, and `GET /v1/me/invitations` lists nothing for an email that
  claim does not verify.

  Each refusal the `membership` processor replies with reaches the status
  the exported document gives it, with an example naming its kind (AC-19,
  the handler half). No access route answers a token or the hash the
  store holds (AC-07, the base half).

  The command send and its Avro coding, and the `membership-query`,
  `user` and `bank-query` components, are stood in for, so no system is
  booted. A stand-in answers only on the test's own thread: a scenario
  running beside this namespace reaches the real function."
  (:require
    [com.repldriven.queenswood.api.access.handlers :as SUT]

    [com.repldriven.queenswood.api.api :as api]
    [com.repldriven.queenswood.api.auth :as auth]

    [com.repldriven.queenswood.bank-query.interface :as banks]
    [com.repldriven.queenswood.membership-query.interface :as memberships]
    [com.repldriven.queenswood.user.interface :as users]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.command.interface :as command]
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.test-system.interface :refer [nom-test>]]

    [clojure.test :refer [deftest is testing]]))

(def ^:private bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7")

(def ^:private user-id "usr.01kprbmgcj35ptc8npmybhh4s7")

(def ^:private invitation-id "inv.01kprbmgcj35ptc8npmybhh4sm")

(def ^:private membership-id "mem.01kprbpdwa9q5n2t7vwsx84a3m")

(def ^:private member-auth
  {:principal-type :user
   :principal-id user-id
   :bank-id bank-id
   :membership {:membership-id membership-id :bank-id bank-id :role :role-admin}
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
  {:bank-id bank-id :membership-id membership-id :invitation-id invitation-id})

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
           "remove-member" "leave-membership" "access-change"]
          (repeat ::schema)))

(defn- with-avro
  [request]
  (assoc request :avro schemas :dispatchers {:memberships ::dispatcher}))

(defn- invitation
  [actor]
  {:invitation-id invitation-id
   :bank-id bank-id
   :email "c.babbage@example.com"
   :role :role-developer
   :status :invitation-status-pending
   :expires-at 1779955200000
   :invited-by actor
   :created-at 1779350400000
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
             (fn [& _] {:membership-id membership-id :bank-id bank-id})
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
         (SUT/accept-invitation
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

(def ^:private exported
  (delay (let [handler (api/app {:interceptors []})
               {:keys [body]} (handler {:request-method :get
                                        :uri "/openapi.json"})]
           (slurp body))))

(def ^:private request
  "One request carrying every path parameter and body field an access
  write reads."
  (with-avro {:auth member-auth
              :parameters {:path {:membership-id membership-id
                                  :invitation-id invitation-id}
                           :body {:email "c.babbage@example.com"
                                  :role :role-developer
                                  :reason "A reason"}}}))

(def ^:private refusals
  "Each kind the `membership` processor refuses with, against a write that
  can meet it: the handler, the route's `[method path]` and the status
  the kind maps to."
  [[:membership/last-owner SUT/change-role
    ["post" "/v1/members/{membership-id}/change-role"] 409]
   [:membership/invalid-status SUT/remove-member
    ["post" "/v1/members/{membership-id}/remove"] 409]
   [:invitation/invalid-status SUT/withdraw-invitation
    ["post" "/v1/invitations/{invitation-id}/withdraw"] 409]
   [:invitation/already-member SUT/invite ["post" "/v1/invitations"] 409]
   [:invitation/already-exists SUT/invite ["post" "/v1/invitations"] 409]
   [:membership/already-exists SUT/accept-invitation
    ["post" "/v1/me/invitations/{invitation-id}/accept"] 409]
   [:invitation/reason-required SUT/invite ["post" "/v1/invitations"] 422]
   [:invitation/not-found SUT/resend-invitation
    ["post" "/v1/invitations/{invitation-id}/resend"] 404]
   [:membership/not-found SUT/leave
    ["post" "/v1/me/memberships/{membership-id}/leave"] 404]
   [:membership/role-not-granted SUT/remove-member
    ["post" "/v1/members/{membership-id}/remove"] 403]])

(defn- documented-types
  "The problem `type` of every example the document gives `status` on
  the operation."
  [document [method path] status]
  (let [examples (get-in document
                         ["paths" path method "responses" (str status)
                          "content" "application/json" "examples"])]
    (into #{}
          (map (fn [[example-name _]]
                 (get-in document
                         ["components" "examples" example-name "value"
                          "type"])))
          examples)))

(deftest each-refusal-reaches-its-documented-status-test
  (nom-test> [document (json/read-str @exported)
              _ (doseq [[kind handler operation status] refusals
                        :let [reason (str kind)]]
                  (testing reason
                    (let [response (standing-in (commanding
                                                 (atom [])
                                                 (fn [_ _]
                                                   {:status "REJECTED"
                                                    :reason reason
                                                    :message "Refused"}))
                                                #(handler request))]
                      (is (= status (:status response)))
                      (is (= status (get-in response [:body :status])))
                      (is (= reason (get-in response [:body :type]))
                          "the type keeps the kind's leading colon")
                      (is (= "REJECTED" (get-in response [:body :title])))
                      (is (contains?
                           (documented-types document operation status)
                           reason)
                          (str "documented at " status " on " operation)))))]))

(def ^:private stored-hash
  "A hash as the store holds it, on every invitation and membership a
  stand-in answers."
  (:token-hash (memberships/new-invitation-token)))

(def ^:private stored-invitation
  (assoc (invitation {:kind :actor-kind-member :principal-id user-id})
         :token-hash
         stored-hash))

(def ^:private stored-membership
  {:membership-id membership-id
   :user-id user-id
   :bank-id bank-id
   :role :role-developer
   :status :membership-status-active
   :invitation-id invitation-id
   :token-hash stored-hash
   :created-at 1779350400000
   :updated-at 1779350400000})

(def ^:private stand-ins
  (merge
   (commanding (atom []) accepted)
   {#'banks/get-bank (fn [_ _] {:name "Ada's Bank"})
    #'users/find-by-id
    (fn [_ _]
      {:user-id user-id :name "Charles Babbage" :email "charles@example.com"})
    #'memberships/find-by-id (fn [& _] stored-membership)
    #'memberships/find-invitation (fn [& _] stored-invitation)
    #'memberships/find-invitation-for-recipient (fn [& _] stored-invitation)
    #'memberships/list-active-by-bank (fn [& _] [stored-membership])
    #'memberships/list-invitations-by-bank (fn [& _] [stored-invitation])
    #'memberships/list-pending-invitations-by-email (fn [& _]
                                                      [stored-invitation])
    #'memberships/list-access-events
    (fn [& _]
      {:access-events [{:access-event-id "aev.01kprbmgcj35ptc8npmybhh4sn"
                        :bank-id bank-id
                        :kind :access-event-kind-invitation-created
                        :actor {:kind :actor-kind-member :principal-id user-id}
                        :invitation-id invitation-id
                        :occurred-at 1779350400000}]})}))

(def ^:private token-shape #"^[A-Za-z0-9_-]{43}$")

(defn- strings
  [body]
  (filter string? (tree-seq coll? seq body)))

(defn- tokens
  [body]
  (filter #(re-matches token-shape %) (strings body)))

(def ^:private recipient
  (assoc request
         :auth
         {:principal-id user-id
          :claims {:email "c.babbage@example.com" :email_verified true}}))

(deftest no-access-route-answers-a-token-test
  (standing-in
   stand-ins
   (fn []
     (doseq [[label handler req]
             [["a create" SUT/invite request]
              ["a resend" SUT/resend-invitation request]
              ["the member list" SUT/list-members request]
              ["the invitation list" SUT/list-invitations request]
              ["an invitation" SUT/get-invitation request]
              ["a member" SUT/get-member request]
              ["the access history" SUT/list-access-events request]
              ["a change of role" SUT/change-role request]
              ["a withdrawal" SUT/withdraw-invitation request]
              ["the recipient's list" SUT/list-my-invitations recipient]
              ["the recipient's read" SUT/get-my-invitation recipient]
              ["an accept" SUT/accept-invitation recipient]
              ["a decline" SUT/decline-invitation recipient]]]
       (testing (str label " answers no token and no hash")
         (let [{:keys [status body]} (handler req)]
           (is (< status 300))
           (is (empty? (tokens body)))
           (is (not-any? #{stored-hash} (strings body)))
           (is (not-any? #{:token :token-hash}
                         (mapcat keys
                          (filter map? (tree-seq coll? seq body)))))))))))

(def ^:private operator-actor
  {:kind :actor-kind-operator :principal-id "queenswood-admin"})

(def ^:private member-actor
  {:kind :actor-kind-member :principal-id user-id :role :role-admin})

(def ^:private history
  [{:access-event-id "aev.01kprbmgcj35ptc8npmybhh4sn"
    :bank-id bank-id
    :kind :access-event-kind-member-removed
    :actor operator-actor
    :subject-user-id subject-id
    :membership-id membership-id
    :role-before :role-developer
    :occurred-at 1779350400000}
   {:access-event-id "aev.01kprbmgcj35ptc8npmybhh4sr"
    :bank-id bank-id
    :kind :access-event-kind-role-changed
    :actor member-actor
    :subject-user-id subject-id
    :membership-id membership-id
    :role-before :role-viewer
    :role-after :role-developer
    :occurred-at 1779350300000}
   {:access-event-id "aev.01kprbmgcj35ptc8npmybhh4ss"
    :bank-id bank-id
    :kind :access-event-kind-invitation-created
    :actor member-actor
    :invitation-id invitation-id
    :occurred-at 1779350200000}])

(defn- counted
  "`f` beside an atom counting its calls by the user id they name."
  [f]
  (let [calls (atom {})]
    {:calls calls
     :f (fn [txn id] (swap! calls update id (fnil inc 0)) (f txn id))}))

(deftest every-access-actor-is-named-test
  (let [named-stand-ins (assoc stand-ins
                               #'users/find-by-id
                               find-person
                               #'memberships/list-access-events
                               (fn [& _] {:access-events history}))]
    (standing-in
     named-stand-ins
     (fn []
       (testing "invite names its inviter"
         (is (= {:kind :actor-kind-member
                 :principal-id user-id
                 :name "Ada Lovelace"}
                (get-in (SUT/invite request) [:body :invited-by]))))
       (testing "a withdrawal names its inviter"
         (is (= "Ada Lovelace"
                (get-in (SUT/withdraw-invitation request)
                        [:body :invited-by :name]))))
       (testing "the invitation list names each inviter"
         (is (= ["Ada Lovelace"]
                (map (comp :name :invited-by)
                     (get-in (SUT/list-invitations request) [:body :items])))))
       (testing "the member list names each inviter"
         (is (= ["Ada Lovelace"]
                (map (comp :name :invited-by)
                     (get-in (SUT/list-members request) [:body :items])))))
       (testing "a recipient's read names the inviter"
         (is (= "Ada Lovelace"
                (get-in (SUT/get-my-invitation recipient)
                        [:body :invited-by :name]))))))
    (testing "the platform's client is named as the platform"
      (standing-in
       (assoc named-stand-ins
              #'memberships/find-invitation
              (fn [& _] (assoc stored-invitation :invited-by operator-actor)))
       (fn []
         (is (= "Queenswood"
                (get-in (SUT/withdraw-invitation request)
                        [:body :invited-by :name]))))))
    (testing "a recipient never reads an inviter's email"
      (standing-in
       (assoc named-stand-ins
              #'users/find-by-id
              (fn [_ _] {:user-id user-id :name "" :email "ada@example.com"}))
       (fn []
         (doseq [response [(SUT/get-my-invitation recipient)
                           (SUT/list-my-invitations recipient)
                           (SUT/decline-invitation recipient)]]
           (is (not-any? #{"ada@example.com"} (strings (:body response))))
           (is (= #{"Ada's Bank"}
                  (set (map (comp :name :invited-by)
                            (let [{:keys [body]} response]
                              (or (:items body) [body]))))))))))
    (testing "a history page names each actor and subject"
      (let [{:keys [calls f]} (counted find-person)]
        (standing-in
         (assoc named-stand-ins #'users/find-by-id f)
         (fn []
           (let [{:keys [status body]} (SUT/list-access-events request)
                 [removed changed created] (:items body)]
             (is (= 200 status))
             (is (= "Queenswood" (get-in removed [:actor :name])))
             (is (= "Charles Babbage" (:subject-name removed)))
             (is (= "Ada Lovelace" (get-in changed [:actor :name])))
             (is (= {:kind :actor-kind-member
                     :principal-id user-id
                     :name "Ada Lovelace"}
                    (:actor created)))
             (is (not (contains? created :subject-name))
                 "no subject, no subject name")
             (is (= {"queenswood-admin" 1 user-id 1 subject-id 1} @calls)
                 "each distinct id is looked up once"))))))))

(def ^:private other-bank-id "bnk.01kprbmgcj35ptc8npmybhh4t9")

(def ^:private accepted-invitation
  (assoc (invitation member-actor)
         :status :invitation-status-accepted
         :accepted-by-user-id subject-id))

(defn- invitation-in
  "A `find-invitation` holding `accepted-invitation` in `bank-id` alone."
  [_ bank invitation]
  (if (and (= bank-id bank) (= invitation-id invitation))
    accepted-invitation
    (error/reject :invitation/not-found
                  {:message "Invitation not found" :invitation-id invitation})))

(defn- get-invitation-as
  [bank invitation]
  (SUT/get-invitation {:auth (assoc member-auth :bank-id bank)
                       :parameters {:path {:invitation-id invitation}}}))

(deftest an-invitation-is-read-in-its-own-bank-test
  (standing-in (assoc stand-ins
                      #'users/find-by-id
                      find-person
                      #'memberships/find-invitation
                      invitation-in
                      #'memberships/list-invitations-by-bank
                      (fn [& _] [accepted-invitation]))
               (fn []
                 (testing "found, as the list shows it"
                   (let [{:keys [status body]}
                         (get-invitation-as bank-id invitation-id)]
                     (is (= 200 status))
                     (is (= invitation-id (:invitation-id body)))
                     (is (= "charles@example.com" (:accepted-email body)))
                     (is (= "Ada Lovelace" (get-in body [:invited-by :name])))
                     (is (= [body]
                            (get-in (SUT/list-invitations {:auth member-auth})
                                    [:body :items]))
                         "the same representation as the list's item")))
                 (testing "another bank's answers 404"
                   (let [{:keys [status body]}
                         (get-invitation-as other-bank-id invitation-id)]
                     (is (= 404 status))
                     (is (= ":invitation/not-found" (:type body)))))
                 (testing "none answers 404"
                   (is (= 404
                          (:status (get-invitation-as
                                    bank-id
                                    "inv.01kprbmgcj35ptc8npmybhh4t0"))))))))

(def ^:private ended-id "mem.01kprbpdwa9q5n2t7vwsx84a3n")

(def ^:private elsewhere-id "mem.01kprbpdwa9q5n2t7vwsx84a3p")

(defn- membership-by-id
  [_ id]
  (condp = id
    membership-id stored-membership
    ended-id (assoc stored-membership
                    :membership-id ended-id
                    :status :membership-status-ended)
    elsewhere-id (assoc stored-membership
                        :membership-id elsewhere-id
                        :bank-id other-bank-id)
    (error/reject :membership/not-found
                  {:message "Membership not found" :membership-id id})))

(defn- get-member-as
  [id]
  (SUT/get-member {:auth member-auth
                   :parameters {:path {:membership-id id}}}))

(deftest a-member-is-read-while-active-in-its-own-bank-test
  (standing-in (assoc stand-ins
                      #'users/find-by-id
                      find-person
                      #'memberships/find-by-id
                      membership-by-id)
               (fn []
                 (testing "found, as the list shows it"
                   (let [{:keys [status body]} (get-member-as membership-id)]
                     (is (= 200 status))
                     (is (= [body]
                            (get-in (SUT/list-members {:auth member-auth})
                                    [:body :items])))))
                 (doseq [[label id] [["another bank's" elsewhere-id]
                                     ["an ended one" ended-id]
                                     ["none" "mem.01kprbpdwa9q5n2t7vwsx84a3q"]]]
                   (testing (str label " answers 404")
                     (let [{:keys [status body]} (get-member-as id)]
                       (is (= 404 status))
                       (is (= ":membership/not-found" (:type body)))))))))

(deftest a-create-names-what-it-created-test
  (standing-in
   stand-ins
   (fn []
     (testing "an invitation"
       (let [{:keys [status headers]} (SUT/invite request)]
         (is (= 201 status))
         (is (= {"Location" (str "/v1/invitations/" invitation-id)} headers))))
     (testing "the membership an accept creates"
       (let [{:keys [status headers]} (SUT/accept-invitation recipient)]
         (is (= 201 status))
         (is (= {"Location" (str "/v1/members/" membership-id)} headers)))))))
