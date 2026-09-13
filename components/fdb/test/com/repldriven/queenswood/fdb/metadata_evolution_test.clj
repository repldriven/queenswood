(ns com.repldriven.queenswood.fdb.metadata-evolution-test
  "The meta-data save carrying this version's arrivals has to survive.
  The declaration carries a version, each store the version its record
  type arrived on, each index the version it was added and last modified
  at, and the Record Layer refuses a save that moves an existing index's
  added version, changes a key under the same name, or retires an index
  the previous meta-data never had. Against an empty cluster every save
  is a first save and none of those rules bite, so this seeds a keyspace
  with the meta-data as it stood one version back and then runs the real
  migrate over it.

  The previous state is derived from the declaration. The stores whose
  record type arrived at its version are dropped, and so is every index
  modified at it, since the Record Layer refuses a meta-data whose index
  is newer than it and accepts a new index only when it is newer than
  the meta-data it lands on. Each former index removed at the version
  stands in as an index on its record type's first field, the key it had
  being gone from the declaration, because a former index the old
  meta-data never had must have been added after it. The version goes
  back one.

  The seed is built from a descriptor whose `RecordTypeUnion` has the
  arriving record types' fields removed: the Record Layer refuses to
  build meta-data for a union record type with no primary key, so the
  previous state cannot be expressed as the current descriptor with the
  declaration entries dropped.

  The bank's descriptor and declaration are named as strings and read off
  the classpath, the way `system/fdb.yml` names them, so this checks the
  bank's own schema without `fdb` requiring the bricks that hold it."
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.fdb.keyspace :as keyspace]
    [com.repldriven.queenswood.fdb.meta-data :as meta-data]
    [com.repldriven.queenswood.fdb.system.components :as components]

    [com.repldriven.mono.env.interface :as env]
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.set :as set]
    [clojure.test :refer [deftest is testing]])
  (:import
    (com.apple.foundationdb.record RecordMetaData)
    (com.apple.foundationdb.record.metadata Index)
    (com.apple.foundationdb.record.provider.foundationdb FDBMetaDataStore)
    (com.google.protobuf Descriptors$FileDescriptor)
    (java.util.function Function)))

(def ^:private descriptor
  "com.repldriven.queenswood.schemas.schemas.SchemaProto")

(defn- declaration
  []
  (:record-types (env/config "classpath:fdb/record-types-test.yml" :default)))

(defn- arriving-stores
  "The stores whose record type arrived at the declared version."
  [{:strs [version stores]}]
  (into {}
        (filter (fn [[_ {:strs [since]}]] (= since version)))
        stores))

(defn- arriving-record-types
  [decl]
  (into #{}
        (map (fn [[_ {:strs [record-type]}]] record-type))
        (arriving-stores decl)))

(defn- indexes-modified-at
  "The names of the indexes last modified at the declared version, across
  every store, the ones added at it included."
  [{:strs [version stores]}]
  (into #{}
        (comp (mapcat (fn [[_ {:strs [indexes]}]] indexes))
              (filter (fn [{:strs [modified]}] (= modified version)))
              (map (fn [{:strs [name]}] name)))
        stores))

(defn- indexes-retired-at
  "The names of the former indexes removed at the declared version,
  across every store."
  [{:strs [version stores]}]
  (into #{}
        (comp (mapcat (fn [[_ {:strs [former-indexes]}]] former-indexes))
              (filter (fn [{:strs [removed]}] (= removed version)))
              (map (fn [{:strs [name]}] name)))
        stores))

(defn- first-field
  "The name of `record-type`'s first field, reached through the union's
  field for it."
  [^Descriptors$FileDescriptor file-desc record-type]
  (let [union (.findMessageTypeByName file-desc "RecordTypeUnion")
        field (.findFieldByName union (str "_" record-type))]
    (.getName (first (.getFields (.getMessageType field))))))

(defn- placeholder-index
  [file-desc record-type {:strs [name added]}]
  {"name" name
   "added" added
   "modified" added
   "field" (first-field file-desc record-type)})

(defn- store-before
  "A store that stays, as it stood one version back: the indexes modified
  at the version dropped, and each former index removed at it standing
  in as a placeholder where it was added earlier and forgotten where it
  was added at the version too."
  [file-desc version {:strs [record-type indexes former-indexes] :as store}]
  (let [removed-now (fn [{:strs [removed]}] (= removed version))
        restored (into []
                       (comp (filter removed-now)
                             (filter (fn [{:strs [added]}] (< added version)))
                             (map #(placeholder-index file-desc record-type %)))
                       former-indexes)]
    (cond-> (assoc store
                   "indexes"
                   (into (vec (remove (fn [{:strs [modified]}]
                                        (= modified version))
                                      indexes))
                         restored))
            (some? former-indexes)
            (assoc "former-indexes"
                   (vec (remove removed-now former-indexes))))))

(defn- previous-declaration
  "The declaration as it stood one version back."
  [file-desc {:strs [version stores] :as decl}]
  (let [arrived (set (keys (arriving-stores decl)))]
    (assoc decl
           "version" (dec version)
           "stores" (into {}
                          (keep (fn [[name store]]
                                  (when-not (arrived name)
                                    [name
                                     (store-before file-desc
                                                   version
                                                   store)])))
                          stores))))

(defn- union-without
  [^Descriptors$FileDescriptor file-desc record-types]
  (let [fields (into #{} (map #(str "_" %)) record-types)
        builder (.toBuilder (.toProto file-desc))]
    (doseq [i (range (.getMessageTypeCount builder))]
      (let [message (.getMessageTypeBuilder builder i)]
        (when (= "RecordTypeUnion" (.getName message))
          (doseq [j (reverse (range (.getFieldCount message)))]
            (when (fields (.getName (.getField message j)))
              (.removeField message j))))))
    (Descriptors$FileDescriptor/buildFrom
     (.build builder)
     (into-array Descriptors$FileDescriptor (.getDependencies file-desc)))))

(defn- seed-meta-data!
  [record-db path meta]
  (.run record-db
        ^Function
        (fn [ctx]
          (.saveRecordMetaData (FDBMetaDataStore. ctx (keyspace/path path))
                               meta)
          nil)))

(defn- migrate-meta-data!
  [record-db path decl]
  ((:system/start components/meta-store)
   {:system/config {:record-db record-db
                    :path path
                    :descriptor descriptor
                    :metadata decl
                    :migrate true}}))

(defn- stored-meta-data
  [record-db path]
  (.run record-db
        ^Function
        (fn [ctx]
          (.getRecordMetaData (FDBMetaDataStore. ctx (keyspace/path path))))))

(defn- index-versions
  [^RecordMetaData md]
  (into {}
        (map (fn [^Index idx] [(.getName idx) (.getAddedVersion idx)]))
        (.getAllIndexes md)))

(defn- index-names [md] (set (keys (index-versions md))))

(defn- record-type-names
  [^RecordMetaData md]
  (set (keys (.getRecordTypes md))))

(defn- since-versions
  [^RecordMetaData md names]
  (into {}
        (map (fn [name] [name
                         (.getSinceVersion (get (.getRecordTypes md)
                                                name))]))
        names))

(defn- reason
  [anomaly]
  (pr-str (dissoc (error/payload anomaly) :exception :stack-trace)))

(deftest saving-this-versions-arrivals-over-previous-meta-data-test
  (with-test-system
   [sys "classpath:fdb/application-test.yml"]
   (let [record-db (system/instance sys [:fdb :record-db])
         path (str "meta-evolution-" (utility/uuidv7))
         file-desc (meta-data/file-descriptor descriptor)
         current (declaration)
         version (get current "version")
         arriving (arriving-record-types current)
         modified (indexes-modified-at current)
         retired (indexes-retired-at current)
         previous (previous-declaration file-desc current)]
     (testing "the declaration's version is one past the previous"
       (is (= (inc (get previous "version")) version)))
     (seed-meta-data!
      record-db
      path
      (#'meta-data/build* (union-without file-desc arriving) previous))
     (let [before (stored-meta-data record-db path)]
       (testing "the keyspace starts on meta-data that predates the arrivals"
         (is (empty? (set/intersection arriving (record-type-names before))))
         (is (empty? (set/intersection modified (index-names before)))))
       (let [migrated (migrate-meta-data! record-db path current)]
         (testing "the migrate saved rather than refusing the save"
           (is (not (error/anomaly? migrated)) (reason migrated))))
       (let [after (stored-meta-data record-db path)]
         (testing "the migrate applied, rather than logging already-current"
           (is (< (.getVersion before) (.getVersion after))))
         (testing "the arriving record types are in the stored meta-data"
           (is (= arriving
                  (set/intersection arriving (record-type-names after)))))
         (testing "so is every index modified at the version"
           (is (= modified (set/intersection modified (index-names after)))))
         (testing "and every index retired at the version is gone"
           (is (empty? (set/intersection retired (index-names after)))))
         (testing
           "and each arriving record type carries the version it arrived on,
                  without which the Record Layer refuses the save"
           (is (= (zipmap arriving (repeat version))
                  (since-versions after arriving))))
         (testing
           "and every index the previous meta-data defined and the version
                  keeps holds the version it was added on, which is what the
                  Record Layer refuses a save for moving"
           (let [was (apply dissoc (index-versions before) retired)]
             (is (= was (select-keys (index-versions after) (keys was)))))))))))
