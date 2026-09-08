(ns com.repldriven.queenswood.api.auth-test
  (:require
    [com.repldriven.queenswood.api.auth :as SUT]

    [com.repldriven.queenswood.membership.interface :as memberships]
    [com.repldriven.queenswood.user.interface :as users]

    [com.repldriven.mono.identity-provider.interface :as identity-provider]
    [com.repldriven.mono.utility.interface :as util]

    [buddy.sign.jwt :as jwt]

    [clojure.test :refer [deftest is testing]])
  (:import
    (java.security KeyPair)))

(def ^:private issuer "https://identity.test/realms/queenswood")

(def ^:private audience "queenswood-api-test")

(def ^:private console-client-id "queenswood-console")

(def ^:private provider (identity-provider/local-provider {:issuer issuer}))

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
  [token]
  (cond-> {:identity-providers [provider]
           :expected-audiences [audience]
           :user-client-ids [console-client-id]}
          token
          (assoc :headers {"authorization" (str "Bearer " token)})))

(defn- authenticate
  [token]
  ((:enter SUT/authenticate) {:request (request token)}))

(defn- authenticated-auth
  [claims]
  (get-in (authenticate (sign-token claims)) [:request :auth]))

(defn- authorize
  [roles security]
  ((:enter SUT/authorize)
   {:request (cond-> {:reitit.core/match {:data {:openapi {}}}}
                     security
                     (assoc-in [:reitit.core/match :data :openapi :security]
                      security)
                     roles
                     (assoc :auth {:roles roles}))}))

(def ^:private user-row
  {:user-id "usr-1"
   :issuer issuer
   :sub "sub-1"
   :email "ada@example.test"
   :name "Ada Lovelace"})

(def ^:private membership-row {:user-id "usr-1" :bank-id "bank-abc"})

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
      (is (= #{:org} (:roles auth)))
      (is (some? (:token-jti auth)))))
  (testing "the admin realm role adds admin and clears bank-id"
    (let [auth (authenticated-auth {:azp "queenswood-admin"
                                    :sub "queenswood-admin"
                                    :aud [audience]
                                    :realm_access {:roles ["org" "admin"]}})]
      (is (= :service (:principal-type auth)))
      (is (= "queenswood-admin" (:principal-id auth)))
      (is (nil? (:bank-id auth)))
      (is (= #{:org :admin} (:roles auth)))))
  (testing "a user client id yields a user principal"
    (with-redefs [users/upsert-by-sub (fn [_txn _claims] user-row)
                  memberships/list-by-user (fn [_txn _user-id]
                                             [membership-row])]
      (let [auth (authenticated-auth {:azp console-client-id
                                      :sub "sub-1"
                                      :aud [audience]
                                      :email "ada@example.test"
                                      :name "Ada Lovelace"})]
        (is (= :user (:principal-type auth)))
        (is (= "usr-1" (:principal-id auth)))
        (is (= issuer (:issuer auth)))
        (is (= "sub-1" (:sub auth)))
        (is (= user-row (:user auth)))
        (is (= [membership-row] (:memberships auth)))
        (is (= "bank-abc" (:bank-id auth)))
        (is (= #{:user :org} (:roles auth)))))))

(deftest authorize-test
  (testing "a route without security passes"
    (let [ctx {:request {:reitit.core/match {:data {:openapi {}}}
                         :auth {:roles #{:org}}}}]
      (is (= ctx ((:enter SUT/authorize) ctx)))))
  (testing "an empty role set is 401 auth/unauthenticated"
    (let [ctx (authorize nil [{"bearerAuth" ["org"]}])]
      (is (= 401 (get-in ctx [:response :status])))
      (is (= "auth/unauthenticated" (get-in ctx [:response :body :type])))))
  (testing "a disjoint role set is 403 auth/forbidden"
    (let [ctx (authorize #{:user} [{"bearerAuth" ["admin"]}])]
      (is (= 403 (get-in ctx [:response :status])))
      (is (= "auth/forbidden" (get-in ctx [:response :body :type])))))
  (testing "an intersecting set passes"
    (let [ctx (authorize #{:user :org} [{"bearerAuth" ["org"]}])]
      (is (nil? (:response ctx)))
      (is (= #{:user :org} (get-in ctx [:request :auth :roles]))))))

(deftest required-roles-test
  (testing "explicit roles become the required set"
    (testing "a principal holding one of them passes"
      (let [ctx (authorize #{:admin} [{"bearerAuth" ["org" "admin"]}])]
        (is (nil? (:response ctx)))))
    (testing "a principal holding none of them is refused"
      (let [ctx (authorize #{:user} [{"bearerAuth" ["org" "admin"]}])]
        (is (= 403 (get-in ctx [:response :status])))))))
