(ns com.repldriven.queenswood.personal-data.interface-test
  "The clearance over records written before ADR-0045, each in the shape
  it was stored in: what the platform no longer keeps is cleared, what
  it still keeps is left, and a second run clears nothing."
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.personal-data.interface :as SUT]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.idv.interface :as idv]
    [com.repldriven.queenswood.person-identification.interface :as
     person-identification]
    [com.repldriven.queenswood.schema.interface :as schema]
    [com.repldriven.queenswood.zyphe-relay.interface :as zyphe-relay]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.edn :as edn]
    [clojure.test :refer [deftest is testing]]))

(defn- save
  [config store-name record]
  (fdb/transact config
                (fn [txn] (fdb/save-record (fdb/open txn store-name) record))))

(defn- load-record
  [config store-name parse & pk]
  (fdb/transact config
                (fn [txn]
                  (some-> (apply fdb/load-record (fdb/open txn store-name) pk)
                          parse))))

(defn- intent
  [intent-id status]
  {:intent-id intent-id
   :dedup-key intent-id
   :request (pr-str {:bank-id "bnk.1"
                     :verification-id "idv.1"
                     :party-id "pty.1"
                     :email "arthur@example.test"
                     :first-name "Arthur"
                     :last-name "Dent"})
   :status status
   :attempts 0
   :created-at 1})

(defn- event
  [outbox-id event-name]
  {:outbox-id outbox-id
   :dedup-key outbox-id
   :event-name event-name
   :payload (.getBytes "Arthur Dent 1952-03-11")
   :created-at 1})

(deftest clear-test
  (with-test-system
   [sys "classpath:personal-data/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}]
     (nom-test> [_ (save config
                         "person-identifications"
                         (schema/PersonIdentification->java
                          {:party-id "pty.1"
                           :given-name "Arthur"
                           :family-name "Dent"
                           :date-of-birth 19520311
                           :nationality "GB"
                           :address {:street "Country Lane"
                                     :town "Cottington"
                                     :postcode "CT12 4XY"
                                     :country "GBR"}
                           :created-at 1
                           :updated-at 1}))
                 _ (save config
                         "idvs"
                         (schema/Idv->java
                          {:bank-id "bnk.1"
                           :verification-id "idv.1"
                           :party-id "pty.1"
                           :status :idv-status-accepted
                           :created-at 1
                           :updated-at 1
                           :evidence {:document {:outcome
                                                 :idv-evidence-outcome-passed
                                                 :given-names "Arthur"
                                                 :family-name "Dent"
                                                 :date-of-birth "1952-03-11"
                                                 :document-type "passport"}}}))
                 _ (save config
                         "party-national-identifiers"
                         (schema/PartyNationalIdentifier->java
                          {:bank-id "bnk.1"
                           :party-id "pty.1"
                           :type :identifier-type-national-insurance
                           :value "TN000001A"
                           :issuing-country "GB"
                           :created-at 1}))
                 _ (save config
                         "zyphe-outbound-intents"
                         (schema/ZypheOutboundIntent->java (intent "int.1"
                                                                   "settled")))
                 _ (save config
                         "zyphe-outbound-intents"
                         (schema/ZypheOutboundIntent->java (intent "int.2"
                                                                   "pending")))
                 _ (zyphe-relay/save-event config
                                           (event "obx.1" "idv-evidence"))
                 _ (zyphe-relay/save-event config
                                           (event "obx.2" "idv-session-opened"))
                 cleared (SUT/clear config)
                 _ (is (= {:person-identifications 1
                           :idv-evidence 1
                           :national-identifiers 1
                           :intents 1
                           :payloads 1}
                          cleared))])
     (testing "the person's names are moved to a record of their own"
       (let [pi (person-identification/get-person-identification config
                                                                 "pty.1")]
         (is (= ["Arthur" "Dent"] [(:given-name pi) (:family-name pi)]))
         (is (not-any? #(contains? pi %)
                       [:date-of-birth :nationality :address]))))
     (testing "no record holding the date of birth is left"
       (is (empty? (:records (fdb/transact
                              config
                              (fn [txn]
                                (fdb/scan-records
                                 (fdb/open txn "person-identifications")
                                 {:limit 10})))))))
     (testing "the evidence keeps its outcome and document type alone"
       (let [document (get-in (idv/get-idv config "bnk.1" "idv.1")
                              [:evidence :document])]
         (is (= :idv-evidence-outcome-passed (:outcome document)))
         (is (= "passport" (:document-type document)))
         (is (every? empty?
                     ((juxt :given-names :family-name :date-of-birth)
                      document)))))
     (testing "no national identifier is left"
       (is (empty? (:records (fdb/transact
                              config
                              (fn [txn]
                                (fdb/scan-records
                                 (fdb/open txn "party-national-identifiers")
                                 {:limit 10})))))))
     (testing "a settled intent keeps its ids, a pending one everything"
       (let [request (fn [id]
                       (edn/read-string
                        (:request (load-record config
                                               "zyphe-outbound-intents"
                                               schema/pb->ZypheOutboundIntent
                                               id))))]
         (is (= {:bank-id "bnk.1" :verification-id "idv.1" :party-id "pty.1"}
                (request "int.1")))
         (is (= "arthur@example.test" (:email (request "int.2"))))))
     (testing "evidence payloads are cleared, other events left"
       (let [payload (fn [id]
                       (:payload (load-record config
                                              "zyphe-outbox"
                                              schema/pb->ZypheOutboxEvent
                                              id)))]
         (is (= "cleared" (String. ^bytes (payload "obx.1") "UTF-8")))
         (is (= "Arthur Dent 1952-03-11"
                (String. ^bytes (payload "obx.2") "UTF-8")))))
     (testing "a second run clears nothing"
       (is (= {:person-identifications 0
               :idv-evidence 0
               :national-identifiers 0
               :intents 0
               :payloads 0}
              (SUT/clear config)))))))
