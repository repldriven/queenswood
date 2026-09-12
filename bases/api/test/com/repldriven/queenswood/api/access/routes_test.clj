(ns com.repldriven.queenswood.api.access.routes-test
  "The access surface as the router and the document carry it: every
  route REQ-023 and REQ-024 name is compiled at its method, path and
  gate, every operation an organisation level gates documents the
  `Bank-Id` header by `$ref` exactly once (AC-17), and the invitation
  token is declared on `InvitationWithToken` and on nothing else.

  No system is booted. The router is compiled from an empty
  interceptor context, and the document is built the way `export-spec`
  builds it."
  (:require
    [com.repldriven.queenswood.api.api :as api]
    [com.repldriven.queenswood.api.auth :as auth]

    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.test-system.interface :refer [nom-test>]]

    [reitit.core :as r]

    [clojure.test :refer [deftest is testing]]))

(def ^:private user-routes
  "REQ-023's routes, each against the one role its gate names."
  {[:get "/v1/me"] "user"
   [:get "/v1/me/invitations"] "user"
   [:get "/v1/me/invitations/{invitation-id}"] "user"
   [:post "/v1/me/invitations/{invitation-id}/accept"] "user"
   [:post "/v1/me/invitations/{invitation-id}/decline"] "user"
   [:post "/v1/me/memberships/{membership-id}/leave"] "user"})

(def ^:private bank-routes
  "REQ-024's routes, each against the one level its gate names."
  {[:get "/v1/members"] "org:viewer"
   [:post "/v1/members/{membership-id}/change-role"] "org:admin"
   [:post "/v1/members/{membership-id}/remove"] "org:admin"
   [:get "/v1/invitations"] "org:viewer"
   [:post "/v1/invitations"] "org:admin"
   [:post "/v1/invitations/{invitation-id}/withdraw"] "org:admin"
   [:post "/v1/invitations/{invitation-id}/resend"] "org:admin"
   [:get "/v1/access-events"] "org:viewer"})

(def ^:private bank-id-header "#/components/parameters/BankIdHeader")

(def ^:private exported
  (delay (let [handler (api/app {:interceptors []})
               {:keys [body]} (handler {:request-method :get
                                        :uri "/openapi.json"})]
           (slurp body))))

(defn- operations
  "Each compiled operation as `[method path]` against its endpoint data."
  []
  (into {}
        (for [[path _ methods] (r/compiled-routes (api/router {:interceptors
                                                               []}))
              method [:get :post :put :patch :delete]
              :let [endpoint (get methods method)]
              :when endpoint]
          [[method path] (:data endpoint)])))

(defn- roles
  [security]
  (into #{} (comp (mapcat vals) cat) security))

(defn- org-level?
  [data]
  (boolean (some (set (map name auth/org-levels))
                 (roles (get-in data [:openapi :security])))))

(defn- header-refs
  [document [method path]]
  (filter #(= bank-id-header (get % "$ref"))
          (get-in document ["paths" path (name method) "parameters"])))

(deftest every-access-route-is-compiled-at-its-gate-test
  (let [ops (operations)]
    (doseq [[route role] (sort (merge user-routes bank-routes))]
      (testing (str route)
        (is (contains? ops route) "exists at its documented method and path")
        (is (fn? (:handler (get ops route))) "and answers with a handler")
        (is (= [{"bearerAuth" [role]}] (get-in ops [route :openapi :security]))
            "gated by exactly the role it documents")))))

(deftest every-org-operation-documents-the-bank-id-header-test
  (nom-test> [document (json/read-str @exported)
              ops (operations)
              org-ops (sort (keys (filter (comp org-level? val) ops)))
              _ (is (seq org-ops))
              _ (testing "every access route REQ-024 names is org-level"
                  (is (every? (set org-ops) (keys bank-routes))))
              _ (doseq [op org-ops]
                  (is (= 1 (count (header-refs document op)))
                      (str "lists Bank-Id once: " op)))
              _ (testing "no route gated user alone lists it"
                  (doseq [op (keys user-routes)]
                    (is (empty? (header-refs document op)) (str op))))
              _ (testing "and the parameter it names is the header"
                  (is (= {"name" "Bank-Id" "in" "header" "required" false}
                         (select-keys (get-in document
                                              ["components" "parameters"
                                               "BankIdHeader"])
                                      ["name" "in" "required"]))))]))

(defn- response-ref
  [document path method status]
  (get-in document
          ["paths" path method "responses" status "content" "application/json"
           "schema" "$ref"]))

(deftest only-invitation-with-token-carries-the-token-test
  (nom-test> [document (json/read-str @exported)
              schemas (get-in document ["components" "schemas"])
              declaring (fn [property]
                          (into #{}
                                (keep (fn [[schema-name schema]]
                                        (when (contains? (get schema
                                                              "properties")
                                                         property)
                                          schema-name)))
                                schemas))
              _ (testing "the token is declared on one shape"
                  (is (= #{"InvitationWithToken"} (declaring "token"))))
              _ (testing "and its hash on none"
                  (is (empty? (declaring "token-hash"))))
              _ (testing "create and resend answer with it"
                  (is (=
                       "#/components/schemas/InvitationWithToken"
                       (response-ref document "/v1/invitations" "post" "201")))
                  (is (= "#/components/schemas/InvitationWithToken"
                         (response-ref document
                                       "/v1/invitations/{invitation-id}/resend"
                                       "post"
                                       "200"))))
              _ (testing "the Role component lists the four roles"
                  (is (= #{"owner" "admin" "developer" "viewer"}
                         (set (get-in schemas ["Role" "enum"])))))]))
