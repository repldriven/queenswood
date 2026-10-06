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
         ;; Under the rig's per-boot prefix, as a relay reads it off the
         ;; store, or the consumer reads an empty log.
         _
         (SUT/process-changelog record-db
                                "test-consumer"
                                "pets"
                                (fn [_ctx changelog-bytes]
                                  (swap! received conj changelog-bytes))
                                {:keyspace-prefix (:keyspace-prefix
                                                   (meta pet-store))})
         _
         (is (= 2 (count @received)))
         _
         (is (= "whiskers-data" (String. ^bytes (first @received))))
         _
         (is (= "rex-data" (String. ^bytes (second @received))))]))))

(defn- handled
  [received tag]
  (count (filter (fn [bytes] (= tag (String. ^bytes bytes))) @received)))

(defn- test-changelog-under-concurrent-writes
  [sys pet-store]
  (let [config {:record-db (system/instance sys [:fdb :record-db])
                :record-store pet-store}
        record-db (system/instance sys [:fdb :record-db])
        opts {:keyspace-prefix (:keyspace-prefix (meta pet-store))}
        consumer (str "concurrent-" (utility/uuidv7))
        first-tag (str "first-" consumer)
        second-tag (str "second-" consumer)
        write (fn [tag]
                (SUT/transact config
                              (fn [txn]
                                (SUT/write-changelog txn
                                                     "pets"
                                                     tag
                                                     (.getBytes ^String tag)))))
        received (atom [])
        appended (atom false)
        handler (fn [_ctx changelog-bytes]
                  (swap! received conj changelog-bytes)
                  (when (compare-and-set! appended false true)
                    (write second-tag)))]
    (testing "an entry appended while a pass runs is handled once, next pass"
      (nom-test> [_ (write first-tag)
                  _
                  (SUT/process-changelog record-db consumer "pets" handler opts)
                  _ (is (= 1 (handled received first-tag))
                        "the pass did not run again for the append")
                  _ (is (= 0 (handled received second-tag)))
                  _
                  (SUT/process-changelog record-db consumer "pets" handler opts)
                  _ (is (= 1 (handled received first-tag)))
                  _ (is (= 1 (handled received second-tag)))]))))

(defn- test-changelog-pass-is-bounded
  [sys pet-store]
  (let [config {:record-db (system/instance sys [:fdb :record-db])
                :record-store pet-store}
        record-db (system/instance sys [:fdb :record-db])
        consumer (str "bounded-" (utility/uuidv7))
        tags (mapv (fn [n] (str consumer "-" n)) (range 3))
        received (atom [])
        handler (fn [_ctx changelog-bytes]
                  (swap! received conj (String. ^bytes changelog-bytes)))
        opts {:keyspace-prefix (:keyspace-prefix (meta pet-store)) :limit 2}
        mine (fn [] (filterv (set tags) @received))]
    (testing "a pass handles at most its limit, and the next the rest"
      (nom-test>
        [_ (SUT/transact config
                         (fn [txn]
                           (run! (fn [tag]
                                   (SUT/write-changelog txn
                                                        "pets"
                                                        tag
                                                        (.getBytes ^String
                                                                   tag)))
                                 tags)))
         ;; A fresh consumer starts at the log's beginning, so its first
         ;; passes drain what other tests wrote before reaching these.
         _ (loop [n 0]
             (let [before (count @received)]
               (SUT/process-changelog record-db consumer "pets" handler opts)
               (is (<= (- (count @received) before) 2))
               (when (and (< (count (mine)) 3) (< n 50)) (recur (inc n)))))
         _ (is (= tags (mine)))]))))

(defn- test-log-without-a-store
  [sys pet-store]
  (let [config {:record-db (system/instance sys [:fdb :record-db])
                :record-store pet-store}
        record-db (system/instance sys [:fdb :record-db])
        log-name (str "log-" (utility/uuidv7))
        received (atom [])
        handler (fn [_ctx changelog-bytes]
                  (swap! received conj (String. ^bytes changelog-bytes)))
        opts {:keyspace-prefix (:keyspace-prefix (meta pet-store))
              :deduplicate? false}]
    (testing "a log no record store owns reads back in commit order"
      (nom-test>
        [_ (SUT/transact config
                         (fn [txn]
                           (SUT/write-log txn log-name "a" (.getBytes "1"))
                           (SUT/write-log txn log-name "a" (.getBytes "2"))))
         _ (SUT/transact config
                         (fn [txn]
                           (SUT/write-log txn log-name "b" (.getBytes "3"))))
         _ (SUT/process-changelog record-db "reader" log-name handler opts)
         _ (is (= ["1" "2" "3"] @received))]))))

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

(defn- test-query-nested-field
  [sys pet-store]
  (let [rex {:pet-id "pet-40" :species "dog" :collar {:tag "T-1"}}
        fido {:pet-id "pet-41" :species "dog" :collar {:tag "T-2"}}
        config {:record-db (system/instance sys [:fdb :record-db])
                :record-store pet-store}]
    (testing "a filter reaches a field of a nested message by its path"
      (nom-test>
        [_
         (SUT/transact config
                       (fn [txn]
                         (let [store (SUT/open txn "pets")]
                           (run! (fn [pet]
                                   (SUT/save-record store
                                                    (test-schema/Pet->java
                                                     pet)))
                                 [rex fido]))))
         found
         (SUT/transact config
                       (fn [txn]
                         (SUT/query-record-compound (SUT/open txn "pets")
                                                    "Pet"
                                                    [[["collar" "tag"] "T-2"]
                                                     ["species" "dog"]])))
         _
         (is (= fido
                (select-keys (utility/record->map (test-schema/pb->Pet found))
                             (keys fido))))]))))

(defn- test-scan-index-records
  [sys pet-store]
  (let [config {:record-db (system/instance sys [:fdb :record-db])
                :record-store pet-store}
        ferret (fn [id] {:pet-id id :name id :species "ferret"})]
    (testing "an index scan returns records under its prefix in key order"
      (nom-test>
        [_
         (SUT/transact config
                       (fn [txn]
                         (let [store (SUT/open txn "pets")]
                           (run! (fn [id]
                                   (SUT/save-record store
                                                    (test-schema/Pet->java
                                                     (ferret id))))
                                 ["pet-93" "pet-90" "pet-92" "pet-91"]))))
         scanned
         (SUT/transact config
                       (fn [txn]
                         (SUT/scan-index-records (SUT/open txn "pets")
                                                 "species_idx"
                                                 ["ferret"]
                                                 {:limit 3})))
         _
         (is (= ["pet-90" "pet-91" "pet-92"]
                (mapv (comp :pet-id test-schema/pb->Pet) scanned))
             "the oldest three, by primary key, and no more")]))))

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
                      (test-query-nested-field sys pet-store)
                      (test-scan-index-records sys pet-store)
                      (test-record-layer-consumer sys pet-store)
                      (test-changelog-under-concurrent-writes sys pet-store)
                      (test-changelog-pass-is-bounded sys pet-store)
                      (test-log-without-a-store sys pet-store))))

(deftest meta-store-test
  (with-test-system [sys "classpath:fdb/application-test.yml"]
                    (let [pet-store (system/instance sys
                                                     [:fdb :pet-meta-store])]
                      (test-record-layer sys pet-store)
                      (test-query-records sys pet-store)
                      (test-query-records-compound sys pet-store)
                      (test-record-layer-consumer sys pet-store)
                      (test-changelog-under-concurrent-writes sys pet-store)
                      (test-changelog-pass-is-bounded sys pet-store)
                      (test-log-without-a-store sys pet-store))))

(deftest stamp-test
  (with-test-system
   [sys "classpath:fdb/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :pet-store])}
         read (fn []
                (SUT/transact config (fn [txn] (SUT/read-stamp txn "pets"))))
         bump (fn []
                (SUT/transact config (fn [txn] (SUT/bump-stamp txn "pets"))))]
     (nom-test> [before (read)
                 _ (is (nil? before) "nothing has bumped it yet")
                 _ (bump)
                 first-stamp (read)
                 _ (is (some? first-stamp))
                 again (read)
                 _ (is (= first-stamp again) "unchanged until bumped")
                 _ (bump)
                 second-stamp (read)
                 _ (is (pos? (compare second-stamp first-stamp))
                       "a later commit's stamp is greater")
                 other (SUT/transact config
                                     (fn [txn] (SUT/read-stamp txn "toys")))
                 _ (is (nil? other) "each name is a stamp of its own")]))))
