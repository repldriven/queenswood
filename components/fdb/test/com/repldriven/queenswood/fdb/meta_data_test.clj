(ns ^:eftest/synchronized com.repldriven.queenswood.fdb.meta-data-test
  "The rules a meta-data save is held to against a store holding data,
  on the pets schema: what the migrator refuses, and what it saves."
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.fdb.interface :as SUT]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]])
  (:import
    (com.google.protobuf Descriptors$FileDescriptor)
    (com.repldriven.queenswood.test_schemas.pets PetProto)
    (com.repldriven.queenswood.test_schemas.schemas TestSchemaProto)))

(def ^:private descriptor
  "com.repldriven.queenswood.test_schemas.schemas.TestSchemaProto")

(defn- index
  [name added modified & {:as key}]
  (merge {"name" name "added" added "modified" modified} key))

(def ^:private v1
  {"version" 1
   "stores" {"pets" {"record-type" "Pet"
                     "indexes" [(index "species_idx" 1 1 "field" "species")
                                (index "name_idx" 1 1 "field" "name")]}
             "owners" {"record-type" "Owner"
                       "primary-key" ["household_id" "owner_id"]
                       "indexes" []}
             "toys" {"record-type" "Toy"
                     "primary-key" ["household_id" "owner_id" "toy_id"]
                     "indexes" []}}})

(defn- at-version [declaration version] (assoc declaration "version" version))

(defn- update-index
  [declaration store name f]
  (update-in declaration
             ["stores" store "indexes"]
             (fn [indexes]
               (mapv #(if (= name (get % "name")) (f %) %) indexes))))

(defn- add-index
  [declaration store index]
  (update-in declaration ["stores" store "indexes"] conj index))

(defn- remove-index
  [declaration store name]
  (update-in declaration
             ["stores" store "indexes"]
             (fn [indexes] (vec (remove #(= name (get % "name")) indexes)))))

(defn- add-former-index
  [declaration store former]
  (update-in declaration
             ["stores" store "former-indexes"]
             (fnil conj [])
             former))

(def ^:private v2-species-rebuilt
  (-> v1
      (at-version 2)
      (update-index "pets"
                    "species_idx"
                    #(-> %
                         (dissoc "field")
                         (assoc "fields" ["species" "name"] "modified" 2)))))

(defn- built
  [declaration]
  (let [meta-data (SUT/build-meta-data descriptor declaration)]
    (is (not (error/anomaly? meta-data)) (pr-str meta-data))
    meta-data))

(defn- reason
  [anomaly]
  (is (= :fdb/meta-data-evolution (error/kind anomaly)) (pr-str anomaly))
  (:message (error/payload anomaly)))

(defn- evolution
  [old new]
  (SUT/validate-meta-data-evolution (built old) (built new)))

(defn- index-versions
  [meta-data name]
  (->> (:indexes (SUT/describe-meta-data meta-data))
       (filter #(= name (:name %)))
       first
       (#(select-keys % [:added :modified]))))

(defn- without-field
  "The test schema's descriptor with `field` dropped from `message`,
  rebuilt from its serialised form as a proto edit would leave it."
  [message field]
  (let [pets (PetProto/getDescriptor)
        pets-proto (.toProto pets)
        message-at (first (keep-indexed (fn [i m]
                                          (when (= message (.getName m)) i))
                                        (.getMessageTypeList pets-proto)))
        message-proto (.getMessageType pets-proto message-at)
        field-at (first (keep-indexed (fn [i f] (when (= field (.getName f)) i))
                                      (.getFieldList message-proto)))
        pets-without (Descriptors$FileDescriptor/buildFrom
                      (-> pets-proto
                          .toBuilder
                          (.setMessageType message-at
                                           (.removeField (.toBuilder
                                                          message-proto)
                                                         field-at))
                          .build)
                      (into-array Descriptors$FileDescriptor
                                  (.getDependencies pets)))]
    (Descriptors$FileDescriptor/buildFrom
     (.toProto (TestSchemaProto/getDescriptor))
     (into-array Descriptors$FileDescriptor [pets-without]))))

(deftest versions-are-declared-not-counted-test
  (testing "an index carries the versions the declaration gives it"
    (let [meta-data (built v1)]
      (is (= 1 (:version (SUT/describe-meta-data meta-data))))
      (is (= {:added 1 :modified 1} (index-versions meta-data "species_idx")))
      (is (= {:added 1 :modified 1} (index-versions meta-data "name_idx")))
      (is (= [] (:former-indexes (SUT/describe-meta-data meta-data))))))
  (testing
    "a bumped modified reaches the index, and the version is the
           declared one rather than the count of indexes"
    (let [meta-data (built v2-species-rebuilt)]
      (is (= 2 (:version (SUT/describe-meta-data meta-data))))
      (is (= {:added 1 :modified 2} (index-versions meta-data "species_idx")))
      (is (= {:added 1 :modified 1} (index-versions meta-data "name_idx")))))
  (testing "a former index is carried with its versions"
    (let [meta-data (built (-> v1
                               (at-version 2)
                               (remove-index "pets" "name_idx")
                               (add-former-index
                                "pets"
                                {"name" "name_idx" "added" 1 "removed" 2})))]
      (is (= [{:name "name_idx" :added 1 :removed 2}]
             (:former-indexes (SUT/describe-meta-data meta-data)))))))

(deftest a-declaration-without-versions-is-refused-test
  (testing "no meta-data version"
    (let [anomaly (SUT/build-meta-data descriptor (dissoc v1 "version"))]
      (is (= :fdb/meta-data-invalid (error/kind anomaly)))
      (is (= [["version"]] (map :path (:problems (error/payload anomaly)))))))
  (testing "an index without an added version"
    (let [anomaly (SUT/build-meta-data
                   descriptor
                   (update-index v1 "pets" "species_idx" #(dissoc % "added")))]
      (is (= :fdb/meta-data-invalid (error/kind anomaly)))
      (is (= [["pets" "indexes" "species_idx"]]
             (map :path (:problems (error/payload anomaly)))))))
  (testing "an index modified past the meta-data version"
    (let [anomaly
          (SUT/build-meta-data
           descriptor
           (update-index v1 "pets" "species_idx" #(assoc % "modified" 2)))]
      (is (= :fdb/meta-data-build (error/kind anomaly)))
      (is (str/includes? (:message (error/payload anomaly))
                         "greater than the meta-data version")))))

(deftest evolution-rules-test
  (testing "a changed key with a bumped modified is a rebuild"
    (is (nil? (evolution v1 v2-species-rebuilt))))
  (testing "a changed key without a bump is refused"
    (is (= "index key expression changed"
           (reason (evolution v1
                              (update-index (at-version v1 2)
                                            "pets"
                                            "species_idx"
                                            #(assoc % "field" "name")))))))
  (testing "a removed index needs a former entry"
    (is (= "index missing in new meta-data"
           (reason
            (evolution v1 (remove-index (at-version v1 2) "pets" "name_idx")))))
    (is (nil? (evolution v1
                         (-> v1
                             (at-version 2)
                             (remove-index "pets" "name_idx")
                             (add-former-index
                              "pets"
                              {"name" "name_idx" "added" 1 "removed" 2}))))))
  (testing "a change needs a higher version"
    (is (= "new meta-data does not have newer version than old meta-data"
           (reason (evolution v1
                              (add-index
                               v1
                               "pets"
                               (index "age_idx" 1 1 "field" "age_months")))))))
  (testing "a new index is added at the new version"
    (is
     (= "new index has version that is not newer than the old meta-data version"
        (reason (evolution v1
                           (add-index
                            (at-version v1 2)
                            "pets"
                            (index "age_idx" 1 1 "field" "age_months"))))))
    (is (nil? (evolution v1
                         (add-index
                          (at-version v1 2)
                          "pets"
                          (index "age_idx" 2 2 "field" "age_months"))))))
  (testing "an index keeps the version it was added at"
    (is (= "new index added version does not match old index added version"
           (reason (evolution v1
                              (update-index
                               (at-version v1 2)
                               "pets"
                               "species_idx"
                               #(assoc % "added" 2 "modified" 2)))))))
  (testing "a removed proto field is refused, however the version moves"
    (let [old (built v1)
          new (SUT/build-meta-data (without-field "Pet" "age_months")
                                   (at-version v1 2))]
      (is (not (error/anomaly? new)) (pr-str new))
      (is (= "field removed from message descriptor"
             (reason (SUT/validate-meta-data-evolution old new)))))))

(deftest save-test
  (with-test-system
   [sys "classpath:fdb/application-test.yml"]
   (let [record-db (system/instance sys [:fdb :record-db])
         path (str "meta-" (utility/uuidv7))
         stored #(SUT/describe-meta-data (SUT/load-meta-data record-db path))]
     (testing "nothing stored yet"
       (is (nil? (SUT/load-meta-data record-db path))))
     (testing "the first save lands"
       (nom-test> [result (SUT/save-meta-data record-db path (built v1))
                   _ (is (= :saved result))
                   _ (is (= 1 (:version (stored))))]))
     (testing "a rebuild at a higher version lands"
       (nom-test> [result (SUT/save-meta-data record-db
                                              path
                                              (built v2-species-rebuilt))
                   _ (is (= :saved result))
                   _ (is (= 2 (:version (stored))))
                   _ (is (= {:added 1 :modified 2}
                            (index-versions (SUT/load-meta-data record-db path)
                                            "species_idx")))]))
     (testing "the same meta-data again is current"
       (nom-test> [result (SUT/save-meta-data record-db
                                              path
                                              (built v2-species-rebuilt))
                   _ (is (= :current result))]))
     (testing "a change without a version bump is refused, not skipped"
       (let [anomaly (SUT/save-meta-data
                      record-db
                      path
                      (built (update-index v2-species-rebuilt
                                           "pets"
                                           "name_idx"
                                           #(assoc % "field" "species"))))]
         (is (str/starts-with? (reason anomaly)
                               "meta-data changed without a version bump"))))
     (testing "older meta-data than the store's is refused"
       (is (= "stored meta-data is newer than this code's"
              (reason (SUT/save-meta-data record-db path (built v1))))))
     (testing "the store is unchanged by what was refused"
       (is (= 2 (:version (stored))))))))
