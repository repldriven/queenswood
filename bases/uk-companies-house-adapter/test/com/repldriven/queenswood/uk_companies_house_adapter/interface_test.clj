(ns com.repldriven.queenswood.uk-companies-house-adapter.interface-test
  "Round-trips a lookup-company command over the bus: the dispatcher
  serialises the request, the adapter fetches from the Companies House
  simulator and caches to FDB, and the reply comes back on the response
  channel deserialised against the `company` schema."
  (:require
    [com.repldriven.queenswood.uk-companies-house-adapter.interface]

    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.schema.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.mono.command-processor.interface]

    [com.repldriven.queenswood.uk-companies-house-simulator.interface :as
     simulator-api]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.command.interface :as command]
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]

    [clojure.test :refer [deftest is testing]]))

(def ^:private known-company "SC998137")

(defn- lookup
  [sys data]
  (let [dispatcher
        (system/instance sys [:companies-dispatcher :dispatcher])
        schemas (system/instance sys [:avro :serde])
        payload (avro/serialize (schemas "lookup-company") data)]
    (command/send dispatcher
                  {:id "cmd.test-lookup"
                   :command "lookup-company"
                   :payload payload})))

(defn- reply->company
  [sys result]
  (let [schemas (system/instance sys [:avro :serde])]
    (avro/deserialize-same (schemas "company") (:payload result))))

(deftest lookup-company-command-test
  (with-test-system
   [sys
    ["classpath:uk-companies-house-adapter/application-test.yml"
     #(assoc-in %
       [:system/defs :uk-companies-house-simulator-server :handler]
       simulator-api/app)]]
   (testing "a known company round-trips and carries the adapter's registry"
     (let [result (lookup sys {:company-number known-company})]
       (is (not (error/anomaly? result)))
       (is (= "ACCEPTED" (:status result)))
       (let [company (reply->company sys result)]
         (is (= known-company (:company-number company)))
         (is (= "uk-companies-house" (:registry-id company)))
         (is (some? (:company-name company))))))
   (testing "an unknown number is rejected under a vendor-neutral kind"
     (let [result (lookup sys {:company-number "99999999"})]
       (is (= "REJECTED" (:status result)))
       (is (= ":company/not-found" (:reason result)))))))
