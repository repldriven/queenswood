(ns com.repldriven.queenswood.api.api-test
  "A route that names a security scheme and gives it no roles demands
  a token without saying what the token must carry, and an operation
  whose gate names two organisation levels has had a method's level
  stacked on its route's. Either way the gate is not the one declared,
  so the router refuses to build and the service never starts serving
  it. `authorize` reads the gate from each method's compiled endpoint,
  so a read and a write on one path are enforced at their own levels."
  (:require
    [com.repldriven.queenswood.api.api :as SUT]

    [com.repldriven.queenswood.api.auth :as auth]

    [reitit.core :as r]
    [reitit.http :as http]

    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]))

(def ^:private bare-path "/v1/bare")

(def ^:private split-path "/v1/split")

(defn- handler [_] {:status 200})

(defn- gate
  [& roles]
  [{"bearerAuth" (vec roles)}])

(defn- table
  "A one-route table whose security names `roles` for `bearerAuth`."
  [roles]
  [[bare-path
    {:openapi {:security [{"bearerAuth" roles}]}
     :get {:handler handler}}]])

(defn- split-table
  "A one-path table with a read at `get-level` and a write at
  `post-level`, each gate declared under its method."
  [get-level post-level]
  [[split-path
    {:get {:openapi {:security (gate get-level)} :handler handler}
     :post {:openapi {:security (gate post-level)} :handler handler}}]])

(defn- stacked-table
  "A one-path table gated `org:viewer` on the route, with a write whose
  own `org:developer` gate is `security-meta`-marked."
  [security-meta]
  [[split-path
    {:openapi {:security (gate "org:viewer")}
     :get {:handler handler}
     :post {:openapi {:security (with-meta (gate "org:developer")
                                           security-meta)}
            :handler handler}}]])

(defn- authorize
  "Run `authorize` over `method` at `path` in `router`, as a principal
  holding `roles` and a bank."
  [router path method roles]
  ((:enter auth/authorize)
   {:request {:request-method method
              :reitit.core/match (r/match-by-path router path)
              :auth {:roles roles :bank-id "bnk.test"}}}))

(defn- refused?
  [ctx]
  (and (= 403 (get-in ctx [:response :status]))
       (= "auth/forbidden" (get-in ctx [:response :body :type]))))

(def ^:private viewer #{:user auth/org-viewer})

(def ^:private developer #{:user auth/org-viewer auth/org-developer})

(deftest bare-security-routes-names-the-offender-test
  (testing "a scheme with no roles is reported"
    (is (= [bare-path] (auth/bare-security-routes (http/router (table []))))))
  (testing "a scheme with roles is not"
    (is (= [] (auth/bare-security-routes (http/router (table ["org"]))))))
  (testing "a method-level scheme with no roles is reported"
    (is (= [bare-path]
           (auth/bare-security-routes (http/router
                                       [[bare-path
                                         {:get {:openapi {:security (gate)}
                                                :handler handler}}]])))))
  (testing "a route naming no scheme at all is public by design"
    (is (= []
           (auth/bare-security-routes (http/router [[bare-path
                                                     {:openapi {:security []}
                                                      :get {:handler
                                                            handler}}]]))))))

(deftest stacked-level-routes-names-the-offender-test
  (testing "a method level under a gated route stacks on the route's"
    (let [router (http/router (stacked-table nil))]
      (is (= [split-path] (auth/stacked-level-routes router)))
      (is (= [{"bearerAuth" ["org:viewer"]} {"bearerAuth" ["org:developer"]}]
             (get-in (r/match-by-path router split-path)
                     [:result :post :data :openapi :security]))
          "reitit concatenates the method's vector onto the route's")))
  (testing "a replacing method level does not"
    (let [router (http/router (stacked-table {:replace true}))]
      (is (= [] (auth/stacked-level-routes router)))
      (is (= (gate "org:developer")
             (get-in (r/match-by-path router split-path)
                     [:result :post :data :openapi :security])))))
  (testing "a level on each method, none on the route, does not"
    (is (= []
           (auth/stacked-level-routes
            (http/router (split-table "org:viewer" "org:developer"))))))
  (testing "one level beside admin does not"
    (is (= []
           (auth/stacked-level-routes (http/router (table ["org:developer"
                                                           "admin"])))))))

(deftest operations-are-enforced-at-their-own-levels-test
  (testing "a read and a write on one path, each gated under its method"
    (let [router (http/router (split-table "org:viewer" "org:developer"))]
      (is (nil? (:response (authorize router split-path :get viewer))))
      (is (refused? (authorize router split-path :post viewer)))
      (is (nil? (:response (authorize router split-path :post developer))))))
  (testing "a route's gate reaches a method that declares none"
    (let [router (http/router [[split-path
                                {:openapi {:security (gate "org:developer")}
                                 :get {:handler handler}
                                 :post {:handler handler}}]])]
      (is (= (gate "org:developer")
             (get-in (r/match-by-path router split-path)
                     [:result :get :data :openapi :security])))
      (is (refused? (authorize router split-path :get viewer)))
      (is (nil? (:response (authorize router split-path :get developer))))))
  (testing "a replacing method gate is enforced in place of the route's"
    (let [router (http/router (stacked-table {:replace true}))]
      (is (nil? (:response (authorize router split-path :get viewer))))
      (is (refused? (authorize router split-path :post viewer)))
      (is (nil? (:response (authorize router split-path :post developer)))))))

(deftest router-refuses-a-bare-security-scheme-test
  (let [thrown (try (SUT/enforceable-router (http/router (table [])))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (some? thrown) "the router must refuse to build")
    (is (str/includes? (ex-message thrown) bare-path)
        "the message names the offending route")
    (is (str/includes? (ex-message thrown) "Scheme with no roles")
        "the message says which of the two refusals it is")
    (is (= {:bare [bare-path] :stacked []} (ex-data thrown)))))

(deftest router-refuses-a-stacked-level-gate-test
  (let [thrown (try (SUT/enforceable-router (http/router (stacked-table nil)))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (some? thrown) "the router must refuse to build")
    (is (str/includes? (ex-message thrown) split-path)
        "the message names the offending route")
    (is (str/includes? (ex-message thrown)
                       "Gate naming more than one org level")
        "the message says which of the two refusals it is")
    (is (= {:bare [] :stacked [split-path]} (ex-data thrown)))))

(deftest app-builds-the-real-route-table-test
  (let [handler (SUT/app {:interceptors []})
        router (:reitit.core/router (meta handler))]
    (is (fn? handler))
    (is (= [] (auth/bare-security-routes router)))
    (is (= [] (auth/stacked-level-routes router)))))
