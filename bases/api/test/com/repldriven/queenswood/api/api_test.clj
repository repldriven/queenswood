(ns com.repldriven.queenswood.api.api-test
  "A route that names a security scheme and gives it no roles demands
  a token without saying what the token must carry, and a route that
  writes its security under a method key writes it where `authorize`
  never looks. Either way the gate is unenforceable, so the router
  refuses to build and the service never starts serving it."
  (:require
    [com.repldriven.queenswood.api.api :as SUT]

    [com.repldriven.queenswood.api.auth :as auth]

    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [reitit.http :as http]))

(def ^:private bare-path "/v1/bare")

(defn- table
  "A one-route table whose security names `roles` for `bearerAuth`."
  [roles]
  [[bare-path
    {:openapi {:security [{"bearerAuth" roles}]}
     :get {:handler (fn [_] {:status 200})}}]])

(defn- method-table
  "A one-route table whose security is written under `:get`, where
  `authorize` cannot read it, naming `roles` for `bearerAuth`."
  [roles]
  [[bare-path
    {:get {:openapi {:security [{"bearerAuth" roles}]}
           :handler (fn [_] {:status 200})}}]])

(deftest bare-security-routes-names-the-offender-test
  (testing "a scheme with no roles is reported"
    (is (= [bare-path] (auth/bare-security-routes (http/router (table []))))))
  (testing "a scheme with roles is not"
    (is (= [] (auth/bare-security-routes (http/router (table ["org"]))))))
  (testing "a route naming no scheme at all is public by design"
    (is (= []
           (auth/bare-security-routes
            (http/router [[bare-path
                           {:openapi {:security []}
                            :get {:handler (fn [_] {:status 200})}}]]))))))

(deftest method-security-routes-names-the-offender-test
  (testing "a method-level scheme naming roles is reported"
    (is (= [bare-path]
           (auth/method-security-routes (http/router (method-table
                                                      ["admin"]))))))
  (testing "a method-level scheme with no roles is reported"
    (is (= [bare-path]
           (auth/method-security-routes (http/router (method-table []))))))
  (testing "a method-level override of a route-level gate is reported"
    (is (= [bare-path]
           (auth/method-security-routes
            (http/router [[bare-path
                           {:openapi {:security [{"bearerAuth" ["org"]}]}
                            :get {:openapi {:security []}
                                  :handler (fn [_] {:status 200})}}]])))))
  (testing "a route-level gate is where it belongs"
    (is (= [] (auth/method-security-routes (http/router (table ["org"])))))))

(deftest router-refuses-a-bare-security-scheme-test
  (let [thrown (try (SUT/enforceable-router (http/router (table [])))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (some? thrown) "the router must refuse to build")
    (is (str/includes? (ex-message thrown) bare-path)
        "the message names the offending route")
    (is (str/includes? (ex-message thrown) "Scheme with no roles")
        "the message says which of the two refusals it is")
    (is (= {:bare [bare-path] :method-level []} (ex-data thrown)))))

(deftest router-refuses-a-method-level-security-scheme-test
  ;; A gate written under `:post` is advertised by the generated
  ;; OpenAPI and read by nobody: `authorize` sees the route-level data
  ;; only, so the route would serve with no token required. The build
  ;; refuses it even though the scheme names a role.
  (let [thrown (try (SUT/enforceable-router (http/router (method-table
                                                          ["admin"])))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (some? thrown) "the router must refuse to build")
    (is (str/includes? (ex-message thrown) bare-path)
        "the message names the offending route")
    (is (str/includes? (ex-message thrown) "Security under a method key")
        "the message says which of the two refusals it is")
    (is (= {:bare [] :method-level [bare-path]} (ex-data thrown)))))

(deftest app-builds-the-real-route-table-test
  ;; The table the service actually serves declares roles everywhere it
  ;; declares a scheme, and declares them on the route rather than
  ;; under a method, so it builds. The scenario suite exercises it;
  ;; this holds the build itself.
  (let [handler (SUT/app {:interceptors []})
        router (:reitit.core/router (meta handler))]
    (is (fn? handler))
    (is (= [] (auth/bare-security-routes router)))
    (is (= [] (auth/method-security-routes router)))))
