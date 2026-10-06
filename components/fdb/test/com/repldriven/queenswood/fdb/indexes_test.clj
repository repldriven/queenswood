(ns com.repldriven.queenswood.fdb.indexes-test
  "An index added to a store already past the few hundred records the
  Record Layer builds inline is left disabled when the store opens, so a
  migrate has to build it before anything reads it."
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.fdb.system.components :as components]

    [com.repldriven.queenswood.test-schema.interface :as test-schema]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]])
  (:import
    (com.apple.foundationdb.record.provider.foundationdb FDBRecordStore)))

(def ^:private descriptor
  "com.repldriven.queenswood.test_schemas.schemas.TestSchemaProto")

(def ^:private pets-count
  "More pets than the Record Layer rebuilds an index over inline."
  300)

(defn- declaration
  [version indexes]
  {"version" version
   "stores" {"pets" {"record-type" "Pet" "indexes" indexes}
             "owners" {"record-type" "Owner"
                       "primary-key" ["household_id" "owner_id"]
                       "indexes" []}
             "toys" {"record-type" "Toy"
                     "primary-key" ["household_id" "owner_id" "toy_id"]
                     "indexes" []}}})

(def ^:private species-index
  {"name" "species_idx" "added" 2 "modified" 2 "field" "species"})

(defn- migrate
  [record-db prefix decl]
  ((:system/start components/meta-store)
   {:system/config {:record-db record-db
                    :path "meta"
                    :descriptor descriptor
                    :metadata decl
                    :keyspace-prefix prefix
                    :migrate true}}))

(defn- save-pets
  [record-db open-store]
  (doseq [batch (partition-all 100 (range pets-count))]
    (fdb/transact {:record-db record-db :record-store open-store}
                  (fn [txn]
                    (doseq [i batch]
                      (fdb/save-record (fdb/open txn "pets")
                                       (test-schema/Pet->java
                                        {:pet-id (str "pet-" i)
                                         :name (str "Pet " i)
                                         :species (if (even? i) "cat" "dog")
                                         :age-months 12}))))
                  :test/save-pets
                  "Failed to save pets")))

(deftest a-migrate-builds-an-index-a-large-store-left-disabled-test
  (with-test-system
   [sys "classpath:fdb/application-test.yml"]
   (let [record-db (system/instance sys [:fdb :record-db])
         prefix (str (utility/uuidv7))
         first-open (migrate record-db prefix (declaration 1 []))]
     (is (not (error/anomaly? first-open)) (pr-str first-open))
     (save-pets record-db first-open)
     (let [second-open
           (migrate record-db prefix (declaration 2 [species-index]))]
       (testing "the migrate adding the index succeeds"
         (is (not (error/anomaly? second-open))))
       (testing "and leaves the index readable over every pet"
         (is (true? (fdb/transact
                     {:record-db record-db :record-store second-open}
                     (fn [txn]
                       (.isIndexReadable ^FDBRecordStore (fdb/open txn "pets")
                                         "species_idx"))
                     :test/index-state
                     "Failed to read the index state"))))))))
