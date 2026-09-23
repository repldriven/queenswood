(ns com.repldriven.queenswood.fdb.interface-test
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.fdb.interface :as SUT]

    [com.repldriven.queenswood.test-schema.interface :as test-schema]

    [com.repldriven.mono.error.interface :refer [nom->]]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(defn- test-str-kv
  [sys]
  (let [db (system/instance sys [:fdb :db])]
    (testing "can store and retrieve string values as raw KV"
      (nom-test> [_ (SUT/set-str db "test-key" "test-value")
                  result
                  (SUT/get-str db "test-key")
                  _
                  (is (= "test-value" result))]))))

(defn- test-proto-kv
  [sys]
  (let [whiskers
        {:pet-id "pet-1" :name "Whiskers" :species "cat" :age-months 24}
        db (system/instance sys [:fdb :db])]
    (testing "can store and retrieve Pet records as raw KV"
      (nom-test> [_ (SUT/set-bytes db "pet/1" (test-schema/Pet->pb whiskers))
                  retrieved
                  (nom-> (SUT/get-bytes db "pet/1") test-schema/pb->Pet)
                  _
                  (is (= whiskers (utility/record->map retrieved)))]))))

(defn- test-record-layer
  [sys pet-store]
  (let [whiskers
        {:pet-id "pet-1" :name "Whiskers" :species "cat" :age-months 24}
        config {:record-db (system/instance sys [:fdb :record-db])
                :record-store pet-store}]
    (testing "can save and load Pet records via FDB Record Layer"
      (nom-test> [_
                  (SUT/transact
                   config
                   (fn [txn]
                     (SUT/save-record (SUT/open txn "pets")
                                      (test-schema/Pet->java whiskers))))
                  retrieved
                  (nom-> (SUT/transact
                          config
                          (fn [txn]
                            (SUT/load-record (SUT/open txn "pets") "pet-1")))
                         test-schema/pb->Pet)
                  _
                  (is (= whiskers (utility/record->map retrieved)))
                  batch
                  (SUT/transact
                   config
                   (fn [txn]
                     (SUT/load-records (SUT/open txn "pets")
                                       ["pet-1" ["pet-nobody"] "pet-1"])))
                  _ (is (= [whiskers nil whiskers]
                           (mapv #(some-> %
                                          test-schema/pb->Pet
                                          utility/record->map)
                                 batch))
                        "one result per key, in order, nil for an absent one")]))))

(defn- test-record-layer-consumer
  [sys pet-store]
  (let [whiskers
        {:pet-id "pet-20" :name "Whiskers" :species "cat" :age-months 24}
        rex {:pet-id "pet-21" :name "Rex" :species "dog" :age-months 36}
        config {:record-db (system/instance sys [:fdb :record-db])
                :record-store pet-store}
        record-db (system/instance sys [:fdb :record-db])
        received (atom [])]
    (testing
      "consumer reads changelog entries and calls handler with
       record bytes"
      (nom-test>
        [_
         (SUT/transact config
                       (fn [txn]
                         (let [store (SUT/open txn "pets")]
                           (SUT/save-record store
                                            (test-schema/Pet->java whiskers))
                           (SUT/write-changelog txn
                                                "pets"
                                                (:pet-id whiskers)
                                                (.getBytes "whiskers-data"))
                           (SUT/save-record store (test-schema/Pet->java rex))
                           (SUT/write-changelog txn
                                                "pets"
                                                (:pet-id rex)
                                                (.getBytes "rex-data")))))
         _
         (SUT/process-changelog record-db
                                "test-consumer"
                                "pets"
                                (fn [_ctx changelog-bytes]
                                  (swap! received conj changelog-bytes)))
         _
         (is (= 2 (count @received)))
         _
         (is (= "whiskers-data" (String. ^bytes (first @received))))
         _
         (is (= "rex-data" (String. ^bytes (second @received))))]))))

(defn- test-query-records
  [sys pet-store]
  (let [whiskers {:pet-id "pet-10"
                  :name "Whiskers"
                  :species "hamster"
                  :age-months 6}
        rex {:pet-id "pet-11" :name "Rex" :species "parrot" :age-months 48}
        config {:record-db (system/instance sys [:fdb :record-db])
                :record-store pet-store}]
    (testing "can query records by field value"
      (nom-test>
        [_
         (SUT/transact config
                       (fn [txn]
                         (let [store (SUT/open txn "pets")]
                           (SUT/save-record store
                                            (test-schema/Pet->java whiskers))
                           (SUT/save-record store
                                            (test-schema/Pet->java rex)))))
         results
         (SUT/transact config
                       (fn [txn]
                         (SUT/query-records (SUT/open txn "pets")
                                            "Pet"
                                            "species"
                                            "hamster")))
         _
         (is (= 1 (count results)))
         retrieved
         (nom-> (first results) test-schema/pb->Pet)
         _
         (is (= whiskers (utility/record->map retrieved)))]))))

(defn- test-query-records-compound
  [sys pet-store]
  (let [clover
        {:pet-id "pet-30" :name "Clover" :species "rabbit" :age-months 12}
        thumper
        {:pet-id "pet-31" :name "Thumper" :species "rabbit" :age-months 12}
        hazel {:pet-id "pet-32" :name "Hazel" :species "rabbit" :age-months 30}
        config {:record-db (system/instance sys [:fdb :record-db])
                :record-store pet-store}
        filters [["species" "rabbit"] ["age_months" 12]]]
    (testing "can query every record matching all field values"
      (nom-test>
        [_
         (SUT/transact config
                       (fn [txn]
                         (let [store (SUT/open txn "pets")]
                           (run! (fn [pet]
                                   (SUT/save-record store
                                                    (test-schema/Pet->java
                                                     pet)))
                                 [clover thumper hazel]))))
         results
         (SUT/transact config
                       (fn [txn]
                         (SUT/query-records-compound (SUT/open txn "pets")
                                                     "Pet"
                                                     filters)))
         _
         (is (= #{clover thumper}
                (set (map (fn [record]
                            (utility/record->map (test-schema/pb->Pet record)))
                          results))))
         indexed
         (SUT/transact config
                       (fn [txn]
                         (SUT/query-records-compound (SUT/open txn "pets")
                                                     "Pet"
                                                     filters
                                                     {:index "species_idx"})))
         _
         (is (= 2 (count indexed)))]))))

(deftest kv-test
  (with-test-system [sys "classpath:fdb/application-test.yml"]
                    (test-str-kv sys)
                    (test-proto-kv sys)))

(deftest store-test
  (with-test-system [sys "classpath:fdb/application-test.yml"]
                    (let [pet-store (system/instance sys [:fdb :pet-store])]
                      (test-record-layer sys pet-store)
                      (test-query-records sys pet-store)
                      (test-query-records-compound sys pet-store)
                      (test-record-layer-consumer sys pet-store))))

(deftest meta-store-test
  (with-test-system [sys "classpath:fdb/application-test.yml"]
                    (let [pet-store (system/instance sys
                                                     [:fdb :pet-meta-store])]
                      (test-record-layer sys pet-store)
                      (test-query-records sys pet-store)
                      (test-query-records-compound sys pet-store)
                      (test-record-layer-consumer sys pet-store))))
