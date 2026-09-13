(ns ^:eftest/synchronized com.repldriven.queenswood.api.auth-test
  (:require
    [com.repldriven.queenswood.api.auth :as SUT]

    [com.repldriven.queenswood.membership.interface :as memberships]
    [com.repldriven.queenswood.user.interface :as users]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.identity-provider.interface :as identity-provider]
    [com.repldriven.mono.utility.interface :as util]

    [buddy.sign.jwt :as jwt]

    [clojure.test :refer [deftest is testing]]
    [clojure.tools.logging.test :as log-test])
  (:import
    (java.security KeyPair)))

(def ^:private issuer "https://identity.test/realms/queenswood")

(def ^:private audience "queenswood-api-test")

(def ^:private console-client-id "queenswood-console")

(def ^:private provider (identity-provider/local-provider {:issuer issuer}))

(def ^:private all-levels (set SUT/org-levels))

(def ^:private viewer-gate [{"bearerAuth" ["org:viewer"]}])

(def ^:private not-a-member "The caller is not a member of this bank")

(def ^:private name-the-bank "Name the bank in the Bank-Id header")

(def ^:private no-bank
  "This route acts on the caller's bank and the token names none")

(defn- sign-token
  "Sign `claims` with the provider's own key, so the test can present
  claims the provider itself would never mint."
  [claims]
  (let [now-s (quot (util/now) 1000)]
    (jwt/sign (merge {:iss issuer
                      :iat now-s
                      :exp (+ now-s 3600)
                      :jti (str "test-jti-" (util/uuidv7))}
                     claims)
              (.getPrivate ^KeyPair (:keypair provider))
              {:alg :rs256
               :header {:kid (:kid provider) :alg "RS256" :typ "JWT"}})))

(defn- request
  ([token] (request token nil))
  ([token bank-id]
   (cond-> {:identity-providers [provider]
            :expected-audiences [audience]
            :user-client-ids [console-client-id]}
           token
           (assoc-in [:headers "authorization"] (str "Bearer " token))
           bank-id
           (assoc-in [:headers "bank-id"] bank-id))))

(defn- authenticate
  ([token] (authenticate token nil))
  ([token bank-id]
   ((:enter SUT/authenticate) {:request (request token bank-id)})))

(defn- authenticated-auth
  ([claims] (authenticated-auth claims nil))
  ([claims bank-id]
   (get-in (authenticate (sign-token claims) bank-id) [:request :auth])))

(defn- authorize-as
  "Run `authorize` over a GET whose compiled endpoint data carries
  `security`, as the principal `auth`."
  [auth security]
  ((:enter SUT/authorize)
   {:request (cond-> {:request-method :get
                      :reitit.core/match {:result {:get {:data {:openapi {}}}}}}
                     security
                     (assoc-in [:reitit.core/match :result :get :data :openapi
                                :security]
                      security)
                     auth
                     (assoc :auth auth))}))

(defn- authorize
  ([roles security] (authorize roles security "bnk.test"))
  ([roles security bank-id]
   (authorize-as (when roles
                   (cond-> {:roles roles}
                           bank-id
                           (assoc :bank-id bank-id)))
                 security)))

(defn- refused-with?
  [ctx detail]
  (and (= 403 (get-in ctx [:response :status]))
       (= "auth/forbidden" (get-in ctx [:response :body :type]))
       (= detail (get-in ctx [:response :body :detail]))))

(def ^:private user-row
  {:user-id "usr-1"
   :issuer issuer
   :sub "sub-1"
   :email "ada@example.test"
   :name "Ada Lovelace"})

(def ^:private membership-row
  {:membership-id "mem.abc"
   :user-id "usr-1"
   :bank-id "bank-abc"
   :role :role-owner})

(def ^:private viewer-row
  {:membership-id "mem.xyz"
   :user-id "usr-1"
   :bank-id "bank-xyz"
   :role :role-viewer})

(def ^:private user-claims
  {:azp console-client-id
   :sub "sub-1"
   :aud [audience]
   :email "ada@example.test"
   :name "Ada Lovelace"})

(def ^:private operator-claims
  (assoc user-claims :realm_access {:roles ["admin"]}))

(defmacro ^:private with-memberships
  "Run `body` as `user-row` holding the active `rows`."
  [rows & body]
  `(with-redefs [users/upsert-by-sub (fn [_txn# _claims#] user-row)
                 memberships/list-active-by-user (fn [_txn# _user-id#] ~rows)]
     ~@body))

(deftest authenticate-test
  (testing "no bearer leaves the context unchanged"
    (let [ctx {:request (request nil)}]
      (is (= ctx ((:enter SUT/authenticate) ctx)))))
  (testing "an issuer matching no provider sets no auth"
    (let [token (sign-token {:iss "https://identity.test/realms/elsewhere"
                             :azp "bank-abc"
                             :sub "bank-abc"
                             :aud [audience]})]
      (is (nil? (get-in (authenticate token) [:request :auth])))))
  (testing "a verification anomaly sets no auth"
    (testing "an expired token"
      (let [now-s (quot (util/now) 1000)
            token (sign-token {:azp "bank-abc"
                               :sub "bank-abc"
                               :aud [audience]
                               :iat (- now-s 3660)
                               :exp (- now-s 60)})]
        (is (nil? (get-in (authenticate token) [:request :auth])))))
    (testing "a foreign audience"
      (let [token (sign-token {:azp "bank-abc"
                               :sub "bank-abc"
                               :aud ["queenswood-api-elsewhere"]})]
        (is (nil? (get-in (authenticate token) [:request :auth]))))))
  (testing "verified service claims yield a service principal"
    (let [auth (authenticated-auth {:azp "bank-abc"
                                    :sub "bank-abc"
                                    :aud [audience]
                                    :realm_access {:roles ["org"]}})]
      (is (= :service (:principal-type auth)))
      (is (= "bank-abc" (:principal-id auth)))
      (is (= "bank-abc" (:bank-id auth)))
      (is (= #{SUT/org-viewer SUT/org-developer} (:roles auth))
          "the realm's `org` role is not carried")
      (is (nil? (:bank-refused auth)))
      (is (some? (:token-jti auth)))))
  (testing "a service principal naming its own bank keeps it"
    (let [auth (authenticated-auth
                {:azp "bank-abc" :sub "bank-abc" :aud [audience]}
                "bank-abc")]
      (is (= "bank-abc" (:bank-id auth)))
      (is (nil? (:bank-refused auth)))))
  (testing "a service principal naming another bank is refused"
    (let [auth (authenticated-auth
                {:azp "bank-abc" :sub "bank-abc" :aud [audience]}
                "bank-xyz")]
      (is (nil? (:bank-id auth)))
      (is (true? (:bank-refused auth)))
      (is (refused-with? (authorize-as auth viewer-gate) not-a-member))))
  (testing "the admin realm role adds admin and every level, and no bank"
    (let [auth (authenticated-auth {:azp "queenswood-admin"
                                    :sub "queenswood-admin"
                                    :aud [audience]
                                    :realm_access {:roles ["org" "admin"]}})]
      (is (= :service (:principal-type auth)))
      (is (= "queenswood-admin" (:principal-id auth)))
      (is (nil? (:bank-id auth)))
      (is (= (into #{:admin} all-levels) (:roles auth)))))
  (testing "the admin client takes the header's bank"
    (let [auth (authenticated-auth {:azp "queenswood-admin"
                                    :sub "queenswood-admin"
                                    :aud [audience]
                                    :realm_access {:roles ["admin"]}}
                                   "bank-xyz")]
      (is (= "queenswood-admin" (:principal-id auth)))
      (is (= "bank-xyz" (:bank-id auth)))
      (is (nil? (:bank-refused auth)))
      (is (nil? (:response (authorize-as auth viewer-gate))))))
  (testing "a user client id yields a user principal"
    (with-memberships [membership-row]
                      (let [auth (authenticated-auth user-claims)]
                        (is (= :user (:principal-type auth)))
                        (is (= "usr-1" (:principal-id auth)))
                        (is (= issuer (:issuer auth)))
                        (is (= "sub-1" (:sub auth)))
                        (is (= user-row (:user auth)))
                        (is (= [membership-row] (:memberships auth)))))))

(deftest bank-id-header-test
  (testing "a member naming their bank takes it and the level held there"
    (with-memberships [membership-row viewer-row]
                      (let [auth (authenticated-auth user-claims "bank-xyz")]
                        (is (= "bank-xyz" (:bank-id auth)))
                        (is (= viewer-row (:membership auth)))
                        (is (= [membership-row viewer-row] (:memberships auth)))
                        (is (= #{:user SUT/org-viewer} (:roles auth)))
                        (is (nil? (:bank-refused auth)))
                        (is (nil? (:response (authorize-as auth
                                                           viewer-gate)))))))
  (testing "a member naming another bank is refused on an org operation"
    (with-memberships
     [membership-row]
     (let [auth (authenticated-auth user-claims "bank-other")]
       (is (nil? (:bank-id auth)))
       (is (nil? (:membership auth)))
       (is (= #{:user} (:roles auth)))
       (is (true? (:bank-refused auth)))
       (is (refused-with? (authorize-as auth viewer-gate) not-a-member))
       (testing "and not on a user operation such as /v1/me"
         (is (nil? (:response (authorize-as auth
                                            [{"bearerAuth" ["user"]}]))))))))
  (testing "no header with one active membership takes it"
    (with-memberships [membership-row]
                      (let [auth (authenticated-auth user-claims)]
                        (is (= "bank-abc" (:bank-id auth)))
                        (is (= membership-row (:membership auth)))
                        (is (= (into #{:user} all-levels) (:roles auth)))
                        (is (nil? (:response (authorize-as auth
                                                           viewer-gate)))))))
  (testing "no header with no membership takes no bank"
    (with-memberships []
                      (let [auth (authenticated-auth user-claims)]
                        (is (nil? (:bank-id auth)))
                        (is (nil? (:membership auth)))
                        (is (= #{:user} (:roles auth)))
                        (is (nil? (:bank-refused auth))))))
  (testing "no header with two active memberships takes no bank"
    (with-memberships
     [membership-row viewer-row]
     (let [auth (authenticated-auth user-claims)]
       (is (nil? (:bank-id auth)))
       (is (nil? (:membership auth)))
       (is (= #{:user} (:roles auth)))
       (is (nil? (:bank-refused auth)))
       (testing "and is refused on an org operation, told to name the bank"
         (is (refused-with? (authorize-as auth viewer-gate) name-the-bank)))
       (testing "and without that detail where admin is also a gate"
         (is (refused-with?
              (authorize-as auth [{"bearerAuth" ["org:developer" "admin"]}])
              "Insufficient privileges"))))))
  (testing "an operator with no header holds every level and no bank"
    (with-memberships []
                      (let [auth (authenticated-auth operator-claims)]
                        (is (nil? (:bank-id auth)))
                        (is (= (into #{:user :admin} all-levels) (:roles auth)))
                        (is (nil? (:bank-refused auth)))
                        (is (refused-with? (authorize-as auth viewer-gate)
                                           no-bank)))))
  (testing "an operator with a header acts on that bank"
    (with-memberships
     []
     (let [auth (authenticated-auth operator-claims "bank-xyz")]
       (is (= "bank-xyz" (:bank-id auth)))
       (is (nil? (:membership auth)))
       (is (= (into #{:user :admin} all-levels) (:roles auth)))
       (is (nil? (:bank-refused auth)))
       (is (nil? (:response (authorize-as auth viewer-gate)))))))
  (testing "an ended membership resolves no bank"
    (with-redefs [users/upsert-by-sub (fn [_txn _claims] user-row)
                  memberships/list-by-user (fn [_txn _user-id]
                                             [(assoc membership-row
                                                     :status
                                                     :membership-status-ended)])
                  memberships/list-active-by-user (fn [_txn _user-id] [])]
      (testing "named in the header"
        (let [auth (authenticated-auth user-claims "bank-abc")]
          (is (nil? (:bank-id auth)))
          (is (nil? (:membership auth)))
          (is (= [] (:memberships auth)))
          (is (refused-with? (authorize-as auth viewer-gate) not-a-member))))
      (testing "with no header"
        (let [auth (authenticated-auth user-claims)]
          (is (nil? (:bank-id auth)))
          (is (= #{:user} (:roles auth))))))))

(deftest levels-test
  (testing "each role carries exactly its level and those below"
    (doseq [[role expected] {:role-viewer #{SUT/org-viewer}
                             :role-developer #{SUT/org-viewer SUT/org-developer}
                             :role-admin #{SUT/org-viewer SUT/org-developer
                                           SUT/org-admin}
                             :role-owner all-levels}]
      (testing (name role)
        (with-memberships [(assoc membership-row :role role)]
                          (is (= (into #{:user} expected)
                                 (:roles (authenticated-auth user-claims))))))))
  (testing "a service principal carries viewer and developer only"
    (is (= #{SUT/org-viewer SUT/org-developer}
           (into #{}
                 (filter all-levels)
                 (:roles (authenticated-auth {:azp "bank-abc"
                                              :sub "bank-abc"
                                              :aud [audience]}))))))
  (testing "an operator with a header carries all four"
    (with-memberships []
                      (is (= all-levels
                             (into #{}
                                   (filter all-levels)
                                   (:roles (authenticated-auth operator-claims
                                                               "bank-xyz")))))))
  (testing "a level passes a gate at or below it and is refused one above"
    (let [developer #{:user SUT/org-viewer SUT/org-developer}]
      (is (nil? (:response (authorize developer viewer-gate))))
      (is (nil? (:response (authorize developer
                                      [{"bearerAuth" ["org:developer"]}]))))
      (is (refused-with? (authorize developer [{"bearerAuth" ["org:admin"]}])
                         "Insufficient privileges"))))
  (testing "org levels and no bank are refused an org-only route"
    (is (refused-with? (authorize #{:user SUT/org-viewer} viewer-gate nil)
                       no-bank))))

(deftest authorize-test
  (testing "a route without security passes"
    (let [ctx {:request {:request-method :get
                         :reitit.core/match {:result {:get {:data {:openapi
                                                                   {}}}}}
                         :auth {:roles #{SUT/org-viewer}}}}]
      (is (= ctx ((:enter SUT/authorize) ctx)))))
  (testing "an empty role set is 401 auth/unauthenticated"
    (let [ctx (authorize nil viewer-gate)]
      (is (= 401 (get-in ctx [:response :status])))
      (is (= "auth/unauthenticated" (get-in ctx [:response :body :type])))))
  (testing "a disjoint role set is 403 auth/forbidden"
    (let [ctx (authorize #{:user} [{"bearerAuth" ["admin"]}])]
      (is (= 403 (get-in ctx [:response :status])))
      (is (= "auth/forbidden" (get-in ctx [:response :body :type])))))
  (testing "an intersecting set passes"
    (let [ctx (authorize #{:user SUT/org-viewer} viewer-gate)]
      (is (nil? (:response ctx)))
      (is (= #{:user SUT/org-viewer} (get-in ctx [:request :auth :roles])))))
  (testing "the gate is read from the request method's endpoint"
    (let [ctx ((:enter SUT/authorize)
               {:request
                {:request-method :post
                 :reitit.core/match
                 {:result {:get {:data {:openapi {:security viewer-gate}}}
                           :post {:data {:openapi {:security
                                                   [{"bearerAuth"
                                                     ["org:developer"]}]}}}}}
                 :auth {:roles #{:user SUT/org-viewer} :bank-id "bnk.test"}}})]
      (is (refused-with? ctx "Insufficient privileges")))))

(deftest required-roles-test
  (testing "explicit roles become the required set"
    (testing "a principal holding one of them passes"
      (let [ctx (authorize #{:admin}
                           [{"bearerAuth" ["org:developer" "admin"]}])]
        (is (nil? (:response ctx)))))
    (testing "a principal holding none of them is refused"
      (let [ctx (authorize #{:user} [{"bearerAuth" ["org:developer" "admin"]}])]
        (is (= 403 (get-in ctx [:response :status])))))))

(defn- logged-messages
  []
  (mapv :message (log-test/the-log)))

(defn- warned-naming?
  [issuer sub]
  (some (fn [{:keys [level message]}]
          (and (= :warn level)
               (re-find (re-pattern (str "(?s)" issuer)) message)
               (re-find (re-pattern (str "(?s)" sub)) message)))
        (log-test/the-log)))

(deftest user-store-failure-test
  (testing
    "an upsert that cannot reach the store answers 503, not a
           principal with no identity"
    (log-test/with-log
     (with-redefs [users/upsert-by-sub (fn [_txn _claims]
                                         (error/fail :fdb/timeout
                                                     {:message
                                                      "Transaction timed out"}))
                   memberships/list-active-by-user (fn [_txn _user-id]
                                                     [membership-row])]
       (let [ctx (authenticate (sign-token user-claims))]
         (is (= 503 (get-in ctx [:response :status])))
         (is (nil? (get-in ctx [:request :auth])))
         (is (warned-naming? issuer "sub-1")
             (str "expected a warning naming the issuer and subject, got: "
                  (pr-str (logged-messages))))))))
  (testing
    "a membership read that fails after a successful upsert is
           reported the same way"
    (log-test/with-log
     (with-redefs [users/upsert-by-sub (fn [_txn _claims] user-row)
                   memberships/list-active-by-user
                   (fn [_txn _user-id]
                     (error/fail :fdb/timeout
                                 {:message "Transaction timed out"}))]
       (let [ctx (authenticate (sign-token user-claims))]
         (is (= 503 (get-in ctx [:response :status])))
         (is (nil? (get-in ctx [:request :auth])))
         (is (warned-naming? issuer "sub-1")))))))

(deftest org-without-bank-test
  (testing "an admin with no membership is refused on an org-only route"
    (let [ctx (authorize #{SUT/org-viewer :admin} viewer-gate nil)]
      (is (= 403 (get-in ctx [:response :status])))
      (is (= "auth/forbidden" (get-in ctx [:response :body :type])))))
  (testing "the same principal passes a route declaring a level and admin"
    (let [ctx (authorize #{SUT/org-viewer :admin}
                         [{"bearerAuth" ["org:viewer" "admin"]}]
                         nil)]
      (is (nil? (:response ctx)))))
  (testing "a member carrying a bank passes an org-only route"
    (let [ctx (authorize #{SUT/org-viewer} viewer-gate "bnk.test")]
      (is (nil? (:response ctx)))))
  (testing
    "an ops-realm admin user carries every level and no bank, so it is
           refused the same way"
    (let [ctx (authorize (into #{:user :admin} all-levels) viewer-gate nil)]
      (is (= 403 (get-in ctx [:response :status])))
      (is (= "auth/forbidden" (get-in ctx [:response :body :type])))))
  (testing "an operator's levels and no bank pass a level gate beside admin"
    (let [ctx (authorize (into #{:user :admin} all-levels)
                         [{"bearerAuth" ["org:developer" "admin"]}]
                         nil)]
      (is (nil? (:response ctx))))))
