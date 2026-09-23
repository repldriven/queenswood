(ns com.repldriven.queenswood.test-api-scenarios.realm-test
  "The console client's `email-verified` mapper: present in every realm
  file that defines the client, and carried as a claim by the tokens the
  deployed realm mints.

  The token test boots an empty Keycloak and POSTs the deployed realm file
  to the Admin API, as the chart's realm-import Job does, rather than
  importing it at container start: Keycloak 26.0's startup import fails
  on the file's service-account user with \"Session not bound to a
  realm\". The file declares its own client scopes, so Keycloak adds no
  default `email` scope and the console's mapper is the only source of
  `email_verified` on its tokens."
  (:require
    [com.repldriven.queenswood.test-api-scenarios.system]

    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]])
  (:import
    (java.util Base64)))

(def ^:private console-client-id "queenswood-console")

(def ^:private realm "queenswood")

(def ^:private user-password "realm-test-password")

(def ^:private email-verified-mapper
  {"name" "email-verified"
   "protocol" "openid-connect"
   "protocolMapper" "oidc-usermodel-property-mapper"
   "config" {"user.attribute" "emailVerified"
             "claim.name" "email_verified"
             "jsonType.label" "boolean"
             "access.token.claim" "true"
             "id.token.claim" "true"}})

(defn- realm-sources
  []
  (let [helm-file (io/file "infra/helm/queenswood/files/keycloak-realm.json")]
    [["classpath keycloak-realm.json" (io/resource "keycloak-realm.json")]
     ["classpath queenswood-realm.json" (io/resource "queenswood-realm.json")]
     [(str (.getPath helm-file) " from the workspace root")
      (when (.exists helm-file) helm-file)]]))

(defn- console-email-verified-mappers
  [realm-representation]
  (->> (get realm-representation "clients")
       (filter (fn [client] (= console-client-id (get client "clientId"))))
       (mapcat (fn [client] (get client "protocolMappers")))
       (filter (fn [mapper] (= "email-verified" (get mapper "name"))))))

(deftest realm-files-map-email-verified-test
  (doseq [[label source] (realm-sources)]
    (testing label
      (is (some? source) (str label " is missing"))
      (when source
        (nom-test> [representation (json/read-str (slurp source))
                    _ (is (= [email-verified-mapper]
                             (console-email-verified-mappers representation)))])))))

(defn- master-admin-token
  [base-url]
  (http/res->edn
   (http/request {:method :post
                  :url (str base-url
                            "/realms/master/protocol/openid-connect/token")
                  :form-params {"grant_type" "password"
                                "client_id" "admin-cli"
                                "username" "admin"
                                "password" "admin"}})))

(defn- admin-request
  [base-url admin-token method path body]
  (http/request (cond-> {:method method
                         :url (str base-url "/admin/realms" path)
                         :headers {"authorization" (str "Bearer " admin-token)
                                   "content-type" "application/json"}}
                        body
                        (assoc :body body))))

(defn- delete-realm
  "Remove the realm a reused Keycloak still holds from the last run,
  so the import below meets the empty Keycloak it expects. 404 is the
  fresh case."
  [base-url admin-token]
  (admin-request base-url admin-token :delete (str "/" realm) nil))

(defn- import-deployed-realm
  [base-url admin-token]
  (admin-request base-url
                 admin-token
                 :post
                 ""
                 (slurp (io/resource "keycloak-realm.json"))))

(defn- create-user
  [base-url admin-token username email-verified]
  (admin-request base-url
                 admin-token
                 :post
                 (str "/" realm "/users")
                 (json/write-str {"username" username
                                  "enabled" true
                                  "email" (str username "@example.com")
                                  "emailVerified" email-verified
                                  "firstName" "Realm"
                                  "lastName" "Test"
                                  "credentials" [{"type" "password"
                                                  "value" user-password
                                                  "temporary" false}]})))

(defn- enable-console-direct-grants
  [base-url admin-token]
  (let [found (http/res->body (admin-request base-url
                                             admin-token
                                             :get
                                             (str "/"
                                                  realm
                                                  "/clients?clientId="
                                                  console-client-id)
                                             nil))
        client (first found)]
    (admin-request base-url
                   admin-token
                   :put
                   (str "/" realm "/clients/" (get client "id"))
                   (json/write-str (assoc client
                                          "directAccessGrantsEnabled"
                                          true)))))

(defn- console-password-grant
  [base-url username]
  (http/res->edn
   (http/request {:method :post
                  :url (str base-url
                            "/realms/"
                            realm
                            "/protocol/openid-connect/token")
                  :form-params {"grant_type" "password"
                                "client_id" console-client-id
                                "username" username
                                "password" user-password
                                "scope" "openid"}})))

(defn- token-claims
  [token]
  (let [payload (second (str/split token #"\."))]
    (json/read-str (String. (.decode (Base64/getUrlDecoder) ^String payload)
                            "UTF-8")
                   :key-fn
                   keyword)))

(deftest deployed-realm-token-carries-email-verified-test
  (with-test-system
   [sys "classpath:test-api-scenarios/deployed-realm-test.yml"]
   (let [base-url (system/instance sys
                                   [:keycloak-container
                                    :container-auth-server-url])]
     (nom-test> [admin (master-admin-token base-url)
                 admin-token (:access_token admin)
                 _ (is (string? admin-token) (pr-str admin))
                 deleted (delete-realm base-url admin-token)
                 _ (is (contains? #{204 404} (:status deleted))
                       (pr-str (:body deleted)))
                 imported (import-deployed-realm base-url admin-token)
                 _ (is (= 201 (:status imported)) (pr-str (:body imported)))
                 verified
                 (create-user base-url admin-token "verified-user" true)
                 _ (is (= 201 (:status verified)) (pr-str (:body verified)))
                 unverified
                 (create-user base-url admin-token "unverified-user" false)
                 _ (is (= 201 (:status unverified)) (pr-str (:body unverified)))
                 grants (enable-console-direct-grants base-url admin-token)
                 _ (is (= 204 (:status grants)) (pr-str (:body grants)))
                 verified-grant (console-password-grant base-url
                                                        "verified-user")
                 unverified-grant (console-password-grant base-url
                                                          "unverified-user")
                 _ (is (string? (:access_token verified-grant))
                       (pr-str verified-grant))
                 _ (is (string? (:access_token unverified-grant))
                       (pr-str unverified-grant))
                 verified-claims (some-> (:access_token verified-grant)
                                         token-claims)
                 unverified-claims (some-> (:access_token unverified-grant)
                                           token-claims)
                 _ (log/info "deployed realm email_verified claims"
                             {:verified-user (:email_verified verified-claims)
                              :unverified-user (:email_verified
                                                unverified-claims)})
                 _ (is (true? (:email_verified verified-claims))
                       (pr-str verified-claims))
                 _ (is (false? (:email_verified unverified-claims))
                       (pr-str unverified-claims))]))))
