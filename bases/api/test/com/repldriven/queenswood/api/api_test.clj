(ns com.repldriven.queenswood.api.api-test
  "A route that names a security scheme and gives it no roles demands
  a token without saying what the token must carry. `authorize` would
  have to guess, so the router refuses to build one and the service
  never starts serving it."
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
                            :get {:handler (fn [_] {:status 200})}}]])))))
  (testing "a method's own security is read as well as the route's"
    (is (= [bare-path]
           (auth/bare-security-routes
            (http/router [[bare-path
                           {:get {:openapi {:security [{"bearerAuth" []}]}
                                  :handler (fn [_] {:status 200})}}]]))))))

(deftest app-refuses-a-bare-security-scheme-test
  (let [thrown (with-redefs [com.repldriven.queenswood.api.api/routes
                             (fn [_] (table []))]
                 (try (SUT/app {:interceptors []})
                      nil
                      (catch clojure.lang.ExceptionInfo e e)))]
    (is (some? thrown) "app must refuse to build")
    (is (str/includes? (ex-message thrown) bare-path)
        "the message names the offending route")
    (is (= [bare-path] (:routes (ex-data thrown))))))

(deftest app-builds-the-real-route-table-test
  ;; The table the service actually serves declares roles everywhere it
  ;; declares a scheme, so it builds. The scenario suite exercises it;
  ;; this holds the build itself.
  (let [handler (SUT/app {:interceptors []})]
    (is (fn? handler))
    (is (= []
           (auth/bare-security-routes (:reitit.core/router (meta handler)))))))
