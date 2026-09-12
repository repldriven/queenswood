(ns com.repldriven.queenswood.api.api-test
  "A route that names a security scheme and gives it no roles demands
  a token without saying what the token must carry, an operation whose
  gate names two organisation levels has had a method's level stacked
  on its route's, and a gate naming the bare `org` role admits no
  principal. Each way the gate is not the one declared, so the router
  refuses to build and the service never starts serving it. `authorize`
  reads the gate from each method's compiled endpoint, so a read and a
  write on one path are enforced at their own levels, and every
  organisation operation in the real route table is gated `org:viewer`
  on a read and `org:developer` on a write, the people writes excepted,
  which are gated `org:admin`."
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

(def ^:private admin #{:user auth/org-viewer auth/org-developer auth/org-admin})

(def ^:private read-methods #{:get})

(def ^:private write-methods #{:post :put :patch :delete})

(def ^:private inbound-transfer "/v1/simulate/banks/{bank-id}/inbound-transfer")

(defn- real-router
  []
  (:reitit.core/router (meta (SUT/app {:interceptors []}))))

(defn- operations
  "Each compiled read or write in `router` as its path, method and gate."
  [router]
  (for [[path _ methods] (r/compiled-routes router)
        method (into read-methods write-methods)
        :let [endpoint (get methods method)]
        :when endpoint]
    {:path path
     :method method
     :security (get-in endpoint [:data :openapi :security])}))

(defn- roles
  [security]
  (into #{} (comp (mapcat vals) cat) security))

(defn- people-write?
  "True for a write on the bank's members or invitations."
  [{:keys [path method]}]
  (boolean (and (write-methods method)
                (or (str/starts-with? path "/v1/members")
                    (str/starts-with? path "/v1/invitations")))))

(defn- org-operation?
  [{:keys [security]}]
  (boolean (some (set (map name auth/org-levels)) (roles security))))

(defn- concrete-path
  "`path` with each `{param}` template segment filled in."
  [path]
  (str/replace path #"\{[^}]+\}" "x"))

(deftest bare-security-routes-names-the-offender-test
  (testing "a scheme with no roles is reported"
    (is (= [bare-path] (auth/bare-security-routes (http/router (table []))))))
  (testing "a scheme with roles is not"
    (is (= []
           (auth/bare-security-routes (http/router (table ["org:viewer"]))))))
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
    (is (= {:bare [bare-path] :stacked [] :bare-org []} (ex-data thrown)))))

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
    (is (= {:bare [] :stacked [split-path] :bare-org []} (ex-data thrown)))))

(deftest bare-org-routes-names-the-offender-test
  (testing "a route gated by the bare org role is reported"
    (is (= [bare-path] (auth/bare-org-routes (http/router (table ["org"]))))))
  (testing "beside admin it is still reported"
    (is (= [bare-path]
           (auth/bare-org-routes (http/router (table ["org" "admin"]))))))
  (testing "a method-level bare org gate is reported"
    (is (= [split-path]
           (auth/bare-org-routes (http/router (split-table "org:viewer"
                                                           "org"))))))
  (testing "a level is not"
    (is (= []
           (auth/bare-org-routes
            (http/router (split-table "org:viewer" "org:developer")))))))

(deftest router-refuses-a-bare-org-gate-test
  (let [thrown (try (SUT/enforceable-router (http/router (table ["org"])))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (some? thrown) "the router must refuse to build")
    (is (str/includes? (ex-message thrown) bare-path)
        "the message names the offending route")
    (is (str/includes? (ex-message thrown) "Bare org gate")
        "the message says which of the refusals it is")
    (is (= {:bare [] :stacked [] :bare-org [bare-path]} (ex-data thrown)))))

(deftest app-builds-the-real-route-table-test
  (let [handler (SUT/app {:interceptors []})
        router (:reitit.core/router (meta handler))]
    (is (fn? handler))
    (is (= [] (auth/bare-security-routes router)))
    (is (= [] (auth/stacked-level-routes router)))
    (is (= [] (auth/bare-org-routes router)))))

(deftest real-route-table-gates-each-operation-at-its-level-test
  (let [ops (operations (real-router))
        org-ops (filter org-operation? ops)]
    (is (seq (filter (comp read-methods :method) org-ops)))
    (is (seq (filter (comp write-methods :method) org-ops)))
    (testing "no operation names the bare org role"
      (is (= []
             (filter (fn [op] (contains? (roles (:security op)) "org")) ops))))
    (testing "the simulator's inbound transfer names org:developer and admin"
      (is (= [(gate "org:developer" "admin")]
             (map :security
                  (filter (fn [op] (= inbound-transfer (:path op))) ops)))))
    (testing "the people writes name org:admin"
      (let [people (filter people-write? org-ops)]
        (is (seq people))
        (doseq [{:keys [path method security]} people]
          (is (= (gate "org:admin") security) (str (name method) " " path)))))
    (testing "every other org read names org:viewer and write org:developer"
      (doseq [{:keys [path method security] :as op} org-ops
              :when (and (not= inbound-transfer path) (not (people-write? op)))]
        (is (= (if (read-methods method)
                 (gate "org:viewer")
                 (gate "org:developer"))
               security)
            (str (name method) " " path))))
    (testing "the companies routes name user"
      (let [companies (filter (fn [op]
                                (str/starts-with? (:path op) "/v1/companies"))
                              ops)]
        (is (seq companies))
        (doseq [{:keys [path security]} companies]
          (is (= (gate "user") security) path))))))

(deftest real-route-table-enforces-each-org-operation-test
  (let [router (real-router)]
    (doseq [{:keys [path method] :as op} (filter org-operation?
                                                 (operations router))
            :let [match (r/match-by-path router (concrete-path path))
                  label (str (name method) " " path)]]
      (is (= path (:template match)) label)
      (testing "a viewer holding a bank reads and is refused every write"
        (let [ctx (authorize router (concrete-path path) method viewer)]
          (if (read-methods method)
            (is (nil? (:response ctx)) label)
            (is (refused? ctx) label))))
      (testing
        "a service credential's levels pass every operation but the people
        writes, which an admin passes"
        (let [ctx (authorize router (concrete-path path) method developer)]
          (if (people-write? op)
            (is (refused? ctx) label)
            (is (nil? (:response ctx)) label)))
        (is (nil? (:response
                   (authorize router (concrete-path path) method admin)))
            label)))))
