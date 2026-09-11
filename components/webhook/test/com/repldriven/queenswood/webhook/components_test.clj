(ns com.repldriven.queenswood.webhook.components-test
  "The notification's `data` is the base's first discriminated
  `oneOf`, and nothing but this projection decides what it names.

  The assertions run over the JSON schema malli projects, not over the
  malli form, because the projected schema is what reaches the
  document: a member the projection stops emitting, or a mapping that
  stops agreeing with its members, is caught here rather than in the
  exported document."
  (:require
    [com.repldriven.queenswood.webhook.components :as SUT]

    [com.repldriven.queenswood.cash-account-api.interface :as cash-account-api]

    [clojure.test :refer [deftest is testing]]

    [malli.json-schema :as json-schema]))

(def ^:private projected
  (json-schema/transform (SUT/notification-data SUT/resource-components)))

(defn- component-name
  [ref]
  (subs ref (count "#/components/schemas/")))

(deftest projects-a-discriminated-one-of-test
  (testing "the union discriminates on the envelope's resource-type"
    (is (= "resource-type" (get-in projected [:discriminator :propertyName]))))
  (testing "every resource type the map knows is a member and a mapping"
    (is (= (set (vals SUT/resource-components))
           (set (map (fn [member] (component-name (:$ref member)))
                     (:oneOf projected)))))
    (is (= SUT/resource-components
           (update-vals (get-in projected [:discriminator :mapping])
                        component-name))))
  (testing "a mapping pointer names a component, not an inlined shape"
    (is (every? (fn [[_ pointer]]
                  (re-matches #"#/components/schemas/\w+" pointer))
                (get-in projected [:discriminator :mapping])))))

(deftest every-mapped-component-resolves-test
  (testing
    "each mapped component is declared by a registry this
           component requires"
    (is (= #{} (SUT/unknown-resource-types SUT/resource-components))))
  (testing "CashAccount is the registry entry the mapping resolves to"
    (is (contains? cash-account-api/registry "CashAccount"))))

(deftest a-resource-type-with-no-component-is-caught-test
  (testing
    "a type added to the map whose component no required
           registry declares is named, rather than projecting a
           pointer at nothing"
    (is (= #{"Party"}
           (SUT/unknown-resource-types
            (assoc SUT/resource-components "Party" "Party"))))))

(deftest the-notification-refers-to-the-union-test
  (testing
    "the envelope carries every field the notification
           publishes, and reaches the union by reference"
    (let [entries (into {}
                        (comp (filter vector?)
                              (map (fn [entry] [(first entry) (last entry)])))
                        (get SUT/registry "WebhookNotification"))]
      (is (= #{:notification-id :kind :change-kind :occurred-at :bank-id
               :resource-type :resource-id :status-before :status-after
               :idempotency-key :correlation-id :data}
             (set (keys entries))))
      (is (= [:ref "WebhookNotificationData"] (:data entries)))
      (is (= [:ref "WebhookResourceType"] (:resource-type entries))))))
