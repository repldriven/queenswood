(ns com.repldriven.queenswood.api.webhook.document-test
  "The document's top-level `webhooks` object: every public kind the
  catalogue names, each referring to the notification component rather
  than restating it.

  Two mechanisms meet here and neither is reitit's ordinary path.
  `webhooks` is injected through the `/openapi.json` route's
  `:openapi` data, which the assembler strips only endpoint keys from,
  so the object survives and no handler wrapper is needed for it. The
  notification's schema is another matter: the assembler fills
  `components/schemas` from the route schemas it transforms and then
  replaces the key wholesale, and no route references the
  notification, so `api.clj`'s wrapper merges it in afterwards. Both
  halves are held here, because a `$ref` at nothing is what either one
  failing looks like.

  The document is built the way `export-spec` builds it. No system is
  booted."
  (:require
    [com.repldriven.queenswood.api.api :as api]

    [com.repldriven.queenswood.webhook.interface :as webhook]

    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.test-system.interface :refer [nom-test>]]

    [clojure.string]
    [clojure.test :refer [deftest is testing]]))

(def ^:private notification-ref "#/components/schemas/WebhookNotification")

(def ^:private exported
  (delay (let [handler (api/app {:interceptors []})
               {:keys [body]} (handler {:request-method :get
                                        :uri "/openapi.json"})]
           (slurp body))))

(defn- request-schema
  [path-item]
  (get-in path-item
          ["post" "requestBody" "content" "application/json" "schema"]))

(deftest the-document-lists-every-published-kind-test
  (nom-test>
    [document (json/read-str @exported)
     webhooks (get document "webhooks")
     _
     (testing
       "the object survives the route data it is injected
                          through"
       (is (seq webhooks)))
     _
     (testing
       "one entry per catalogued kind, and cash-account.opened
                          among them"
       (is (= (set (map :kind webhook/published-kinds)) (set (keys webhooks))))
       (is (contains? webhooks "cash-account.opened")))]))

(deftest each-kind-refers-to-the-notification-component-test
  (nom-test> [document (json/read-str @exported)
              webhooks (get document "webhooks")
              _ (testing "the body is a reference, not a shape restated here"
                  (doseq [[kind path-item] webhooks]
                    (testing kind
                      (is (= {"$ref" notification-ref}
                             (request-schema path-item))))))
              _ (testing "and it carries the notification's example"
                  (doseq [[kind path-item] webhooks]
                    (testing kind
                      (is (= {"WebhookNotification"
                              {"$ref"
                               "#/components/examples/WebhookNotification"}}
                             (get-in path-item
                                     ["post" "requestBody" "content"
                                      "application/json" "examples"]))))))]))

(deftest the-notification-component-the-object-names-is-declared-test
  (nom-test>
    [document (json/read-str @exported)
     schemas (get-in document ["components" "schemas"])
     _
     (testing
       "the wrapper put the notification and its union back
                          after the assembler replaced the key"
       (is (contains? schemas "WebhookNotification"))
       (is (contains? schemas "WebhookNotificationData"))
       (is (contains? schemas "WebhookResourceType")))
     union (get schemas "WebhookNotificationData")
     _
     (testing
       "the union is a discriminated oneOf over resource
                          components"
       (is (= "resource-type"
              (get-in union
                      ["discriminator"
                       "propertyName"])))
       (is (seq (get union "oneOf"))))
     _
     (testing
       "whose mapping names a component the document declares,
                          which `$ref` resolution does not check: a
                          mapping value is not a `$ref` key"
       (let [mapping (get-in union ["discriminator" "mapping"])]
         (is (= "#/components/schemas/CashAccount" (get mapping "CashAccount")))
         (doseq [[resource-type pointer] mapping]
           (testing resource-type
             (is (contains? schemas
                            (last (clojure.string/split pointer #"/"))))))))
     _ (testing "and the envelope reaches it by reference"
         (is (= "#/components/schemas/WebhookNotificationData"
                (get-in schemas
                        ["WebhookNotification" "properties"
                         "data" "$ref"]))))]))
