(ns ^:eftest/synchronized com.repldriven.queenswood.api.auth-test
  (:require
    [com.repldriven.queenswood.api.auth :as SUT]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.member-query.interface :as memberships]
    [com.repldriven.queenswood.user.interface :as users]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.identity-provider.interface :as identity-provider]
    [com.repldriven.mono.server.interface :as server]
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

(defn- run
  "Run `interceptors` over `request`, each compiled against `data` where it
  has a compile step, stopping at the first that terminates."
  [interceptors data request]
  (reduce (fn [ctx interceptor]
            (let [{:keys [compile]} interceptor
                  interceptor (if compile (compile data nil) interceptor)]
              (if (or (nil? interceptor) (:response ctx))
                ctx
                ((:enter interceptor) ctx))))
          {:request request}
          interceptors))

(def ^:private identification
  [server/credential server/authenticate-with-provider server/claims->scopes
   SUT/claims->principal])

(defn- authenticate
  ([token] (authenticate token nil))
  ([token bank-id] (run identification {} (request token bank-id))))

(defn- authenticated-auth
  ([claims] (authenticated-auth claims nil))
  ([claims bank-id]
   (get-in (authenticate (sign-token claims) bank-id) [:request :auth])))

(def ^:private gate-data
  "The route data `/v1` carries for the guard and the gate."
  {:scopes SUT/scopes
   :exclusive-scopes SUT/exclusive-scopes
   :unauthorized (errors/unauthenticated-response)
   :forbidden (errors/forbidden-response)})

(defn- authorize-as
  "Run `require-bank` and the gate over an operation whose data carries
  `security`, as the principal `auth`."
  [auth security]
  (run [SUT/require-bank server/require-scopes]
       (cond-> gate-data
               security
               (assoc :openapi {:security security}))
       (cond-> {}
               auth
               (assoc :auth auth
                      :auth-claims {:sub "test"}
                      :auth-scopes (into #{} (map name) (:roles auth))))))

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
  {:member-id "mem.abc" :user-id "usr-1" :bank-id "bank-abc" :role :role-owner})

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
  (testing "a route without security compiles neither guard nor gate"
    (is (nil? ((:compile SUT/require-bank) {:openapi {}} nil)))
    (is (nil? ((:compile server/require-scopes) {:openapi {}} nil))))
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
      (is (= #{:user SUT/org-viewer} (get-in ctx [:request :auth :roles]))))))

(deftest alternative-gates-test
  (let [either [{"bearerAuth" ["org:developer"]} {"bearerAuth" ["admin"]}]]
    (testing "a principal holding either requirement passes"
      (is (nil? (:response (authorize #{:admin} either))))
      (is (nil? (:response (authorize #{SUT/org-developer} either)))))
    (testing "a principal holding neither is refused"
      (is (= 403 (get-in (authorize #{:user} either) [:response :status]))))))

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
