(ns ^:eftest/synchronized com.repldriven.queenswood.api.access.handlers-test
  "The actor a people write records is derived from the principal
  (REQ-028): an operator — a principal carrying `admin` — by its id, and
  anyone else as a member with the role of the membership the call
  resolved to. A recipient's proof is the `Invitation-Token` header's
  hash and the token's email with its `email_verified` claim, and
  `GET /v1/me/invitations` lists nothing for an email that claim does not
  verify.

  Each refusal the `membership` component gives reaches the status the
  exported document gives it, with an example naming its kind (AC-19,
  the handler half). A 43-character token is answered by create and
  resend, and by no other access route, which also never answers the
  hash the store holds (AC-07, the base half).

  The `membership`, `user` and `bank-query` components are stood in for,
  so no system is booted. A stand-in answers only on the test's own
  thread: a scenario running beside this namespace reaches the real
  function."
  (:require
    [com.repldriven.queenswood.api.access.handlers :as SUT]

    [com.repldriven.queenswood.api.api :as api]
    [com.repldriven.queenswood.api.auth :as auth]

    [com.repldriven.queenswood.bank-query.interface :as banks]
    [com.repldriven.queenswood.membership.interface :as memberships]
    [com.repldriven.queenswood.user.interface :as users]

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
    (standing-in {#'memberships/invite (fn [_ _ _ opts]
                                         (reset! seen opts)
                                         (invitation opts))}
                 (fn []
                   (let [response (SUT/invite
                                   {:auth auth
                                    :parameters {:body {:email
                                                        "c.babbage@example.com"
                                                        :role :role-developer
                                                        :reason "A reason"}}})]
                     {:response response :opts @seen})))))

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
    (standing-in
     {#'banks/get-bank (fn [_ _] {:name "Ada's Bank"})
      #'memberships/find-invitation-for-recipient
      (fn [_ _ proof]
        (reset! seen proof)
        (invitation {:actor {:kind :actor-kind-member :principal-id user-id}}))}
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
           @seen))))))

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
  {:auth member-auth
   :parameters
   {:path {:membership-id membership-id :invitation-id invitation-id}
    :body
    {:email "c.babbage@example.com" :role :role-developer :reason "A reason"}}})

(def ^:private refusals
  "Each kind the `membership` component refuses with, against a write
  that can meet it: the component fn standing in, the handler, the
  route's `[method path]` and the status the kind maps to."
  [[(error/reject :membership/last-owner
                  {:message "Make someone else an owner first"})
    #'memberships/change-role SUT/change-role
    ["post" "/v1/members/{membership-id}/change-role"] 409]
   [(error/reject :membership/invalid-status
                  {:message "Membership is not in a state that allows this"})
    #'memberships/remove-member SUT/remove-member
    ["post" "/v1/members/{membership-id}/remove"] 409]
   [(error/reject :invitation/invalid-status
                  {:message "Invitation is not in a state that allows this"})
    #'memberships/withdraw SUT/withdraw-invitation
    ["post" "/v1/invitations/{invitation-id}/withdraw"] 409]
   [(error/reject :invitation/already-member
                  {:message "That address belongs to a member already"})
    #'memberships/invite SUT/invite ["post" "/v1/invitations"] 409]
   [(error/reject :invitation/already-exists
                  {:message "That address has a pending invitation"})
    #'memberships/invite SUT/invite ["post" "/v1/invitations"] 409]
   [(error/reject :membership/already-exists
                  {:message "Already a member of this bank"})
    #'memberships/accept SUT/accept-invitation
    ["post" "/v1/me/invitations/{invitation-id}/accept"] 409]
   [(error/reject :invitation/reason-required
                  {:message "An operator's invitation needs a reason"})
    #'memberships/invite SUT/invite ["post" "/v1/invitations"] 422]
   [(error/reject :invitation/not-found {:message "Invitation not found"})
    #'memberships/resend SUT/resend-invitation
    ["post" "/v1/invitations/{invitation-id}/resend"] 404]
   [(error/reject :membership/not-found {:message "Membership not found"})
    #'memberships/leave SUT/leave
    ["post" "/v1/me/memberships/{membership-id}/leave"] 404]
   [(error/unauthorized :membership/role-not-granted
                        {:message "Your role does not allow this"})
    #'memberships/remove-member SUT/remove-member
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
              _
              (doseq [[anomaly component-fn handler operation status] refusals
                      :let [kind (str (error/kind anomaly))]]
                (testing kind
                  (let [response (standing-in {component-fn (fn [& _] anomaly)}
                                              #(handler request))]
                    (is (= status (:status response)))
                    (is (= status (get-in response [:body :status])))
                    (is (= kind (get-in response [:body :type]))
                        "the type keeps the kind's leading colon")
                    (is (= (if (error/unauthorized? anomaly)
                             "UNAUTHORIZED"
                             "REJECTED")
                           (get-in response [:body :title])))
                    (is (contains? (documented-types document operation status)
                                   kind)
                        (str "documented at " status " on " operation)))))]))

(def ^:private stored-hash
  "A hash as the store holds it, on every invitation and membership a
  stand-in answers."
  (:token-hash (memberships/new-invitation-token)))

(def ^:private stored-invitation
  (assoc (invitation {:actor {:kind :actor-kind-member :principal-id user-id}})
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
  {#'banks/get-bank (fn [_ _] {:name "Ada's Bank"})
   #'users/find-by-id
   (fn [_ _]
     {:user-id user-id :name "Charles Babbage" :email "charles@example.com"})
   #'memberships/invite (fn [& _] stored-invitation)
   #'memberships/resend (fn [& _] stored-invitation)
   #'memberships/withdraw (fn [& _] stored-invitation)
   #'memberships/accept (fn [& _] stored-membership)
   #'memberships/decline (fn [& _] stored-invitation)
   #'memberships/change-role (fn [& _] stored-membership)
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
                       :occurred-at 1779350400000}]})})

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

(deftest the-token-is-answered-by-create-and-resend-only-test
  (standing-in
   stand-ins
   (fn []
     (doseq [[label handler] [["create" SUT/invite]
                              ["resend" SUT/resend-invitation]]]
       (testing (str label " answers a 43-character token")
         (let [{:keys [body]} (handler request)
               {:keys [token]} body]
           (is (re-matches token-shape (str token)))
           (is (= [token] (tokens body)) "and no other token-shaped string")
           (is (not-any? #{stored-hash} (strings body))))))
     (doseq [[label handler req]
             [["the member list" SUT/list-members request]
              ["the invitation list" SUT/list-invitations request]
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
