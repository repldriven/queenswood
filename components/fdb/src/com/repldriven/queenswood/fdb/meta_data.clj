(ns com.repldriven.queenswood.fdb.meta-data
  (:refer-clojure :exclude [name load])
  (:require
    [com.repldriven.queenswood.fdb.keyspace :as keyspace]

    [com.repldriven.mono.error.interface :as error :refer [try-nom]]
    [com.repldriven.mono.log.interface :as log])
  (:import
    (com.apple.foundationdb.record RecordMetaData)
    (com.apple.foundationdb.record.metadata FormerIndex
                                            Index
                                            IndexOptions
                                            IndexTypes
                                            Key$Expressions
                                            MetaDataEvolutionValidator
                                            MetaDataException)
    (com.apple.foundationdb.record.metadata.expressions GroupingKeyExpression
                                                        KeyExpression$FanType)
    (com.apple.foundationdb.record.provider.foundationdb FDBMetaDataStore
                                                         FDBRecordContext
                                                         FDBStoreTimer$Waits)
    (com.google.protobuf Descriptors$FileDescriptor)
    (java.util.function Function)))

(defn file-descriptor
  [descriptor]
  (if (instance? Descriptors$FileDescriptor descriptor)
    descriptor
    (let [clazz (Class/forName descriptor)
          method (.getMethod clazz "getDescriptor" (into-array Class []))]
      (.invoke method nil (into-array Object [])))))

(defn- problem [path message] {:path path :message message})

(defn- version-problems
  [path {:strs [added modified]}]
  (cond-> []
          (not (pos-int? added))
          (conj (problem path "needs a positive added version"))

          (not (pos-int? modified))
          (conj (problem path "needs a positive modified version"))

          (and (pos-int? added) (pos-int? modified) (< modified added))
          (conj (problem path "modified precedes added"))))

(defn- index-problems
  [store-name {:strs [name field fields] :as index}]
  (let [path [store-name "indexes" name]]
    (cond-> (version-problems path index)
            (not (string? name))
            (conj (problem path "needs a name"))

            (not (or (string? field) (sequential? fields)))
            (conj (problem path "needs a field or fields")))))

(defn- former-index-problems
  [store-name {:strs [name added removed]}]
  (let [path [store-name "former-indexes" name]]
    (cond-> []
            (not (string? name))
            (conj (problem path "needs a name"))

            (not (pos-int? added))
            (conj (problem path "needs a positive added version"))

            (not (pos-int? removed))
            (conj (problem path "needs a positive removed version"))

            (and (pos-int? added) (pos-int? removed) (< removed added))
            (conj (problem path "removed precedes added")))))

(defn- store-problems
  [[store-name {:strs [record-type indexes former-indexes]}]]
  (cond-> []
          (not (string? record-type))
          (conj (problem [store-name] "needs a record-type"))

          (not (sequential? indexes))
          (conj (problem [store-name]
                         "needs an indexes list, even an empty one"))

          (sequential? indexes)
          (into (mapcat #(index-problems store-name %) indexes))

          (and (some? former-indexes) (not (sequential? former-indexes)))
          (conj (problem [store-name] "former-indexes must be a list"))

          (sequential? former-indexes)
          (into (mapcat #(former-index-problems store-name %) former-indexes))))

(defn- problems
  [{:strs [version stores]}]
  (cond-> []
          (not (pos-int? version))
          (conj (problem ["version"] "needs a positive version"))

          (not (map? stores))
          (conj (problem ["stores"] "needs a stores map"))

          (map? stores)
          (into (mapcat store-problems stores))))

(defn- fan-type
  [fan-out]
  (if fan-out KeyExpression$FanType/FanOut KeyExpression$FanType/None))

(defn- key-expression
  [{:strs [field fields fan-out nest]}]
  (cond
   fields
   (Key$Expressions/concatenateFields ^java.util.List fields)

   nest
   (.nest (Key$Expressions/field field (fan-type fan-out))
          (key-expression nest))

   :else
   (Key$Expressions/field field (fan-type fan-out))))

(def ^:private index-types {"count" IndexTypes/COUNT "sum" IndexTypes/SUM})

(defn- grouped
  "COUNT groups by every field, SUM by all but the trailing value
  column it sums."
  [idx-type expr]
  (condp = idx-type
    IndexTypes/COUNT (GroupingKeyExpression. expr 0)
    IndexTypes/SUM (GroupingKeyExpression. expr 1)
    expr))

(defn- ->index
  ^Index [{:strs [name unique type added modified] :as index}]
  (let [idx-type (get index-types type IndexTypes/VALUE)]
    (doto (Index. ^String name
                  (grouped idx-type (key-expression index))
                  ^String idx-type
                  (if unique
                    IndexOptions/UNIQUE_OPTIONS
                    IndexOptions/EMPTY_OPTIONS))
      (.setAddedVersion (int added))
      (.setLastModifiedVersion (int modified)))))

(defn- ->former-index
  [{:strs [name added removed]}]
  (FormerIndex. name (int added) (int removed) name))

(defn- set-primary-key
  [builder record-type primary-key]
  (when primary-key
    (.setPrimaryKey (.getRecordType builder record-type)
                    (if (= 1 (count primary-key))
                      (Key$Expressions/field (first primary-key))
                      (Key$Expressions/concatenateFields ^java.util.List
                                                         primary-key)))))

(defn- build*
  [file-desc {:strs [version stores]}]
  (let [builder (.setRecords (RecordMetaData/newBuilder)
                             ^Descriptors$FileDescriptor file-desc)]
    (doseq [[_ {:strs [record-type primary-key indexes former-indexes]}]
            stores]
      (set-primary-key builder record-type primary-key)
      (doseq [index indexes]
        (.addIndex builder ^String record-type (->index index)))
      (doseq [former former-indexes]
        (.addFormerIndex builder (->former-index former))))
    (.setVersion builder (int version))
    (.build builder)))

(defn- log-info
  [^MetaDataException e]
  (into {} (map (fn [[k v]] [k (str v)])) (.getLogInfo e)))

(defn build
  [descriptor declaration]
  (let [found (problems declaration)]
    (if (seq found)
      (error/fail :fdb/meta-data-invalid
                  {:message "FDB record meta-data declaration is invalid"
                   :problems found})
      (try-nom :fdb/meta-data-build
               "FDB record meta-data failed to build"
               (try (build* (file-descriptor descriptor) declaration)
                    (catch MetaDataException e
                      (error/fail :fdb/meta-data-build
                                  {:message (.getMessage e)
                                   :detail (log-info e)
                                   :exception e})))))))

(defn describe
  [^RecordMetaData meta-data]
  {:version (.getVersion meta-data)
   :indexes (->> (.getAllIndexes meta-data)
                 (map (fn [^Index index]
                        {:name (.getName index)
                         :added (.getAddedVersion index)
                         :modified (.getLastModifiedVersion index)
                         :key (str (.getRootExpression index))}))
                 (sort-by :name)
                 vec)
   :former-indexes (->> (.getFormerIndexes meta-data)
                        (map (fn [^FormerIndex former]
                               {:name (.getFormerName former)
                                :added (.getAddedVersion former)
                                :removed (.getRemovedVersion former)}))
                        (sort-by :name)
                        vec)})

(def
  ^{:private true
    :doc
    "The rules a save is held to. Index rebuilds are allowed, so an
  index may change its key expression under its own name provided its
  `modified` version rises, which is what tells a store to rebuild it."}
  rebuilding-validator
  (-> (MetaDataEvolutionValidator/newBuilder)
      (.setAllowIndexRebuilds true)
      .build))

(def
  ^{:private true
    :doc
    "The rules meta-data at the stored version is held to: no rebuilds,
  and so no change of any kind, since a change without a version bump
  is a change the migrator would otherwise skip."}
  unchanged-validator
  (-> (MetaDataEvolutionValidator/newBuilder)
      (.setAllowNoVersionChange true)
      .build))

(defn- evolution-anomaly
  [message ^MetaDataException e ^RecordMetaData old ^RecordMetaData new]
  (error/fail :fdb/meta-data-evolution
              {:message message
               :detail (log-info e)
               :old-version (.getVersion old)
               :new-version (.getVersion new)
               :exception e}))

(defn validate-evolution
  [^RecordMetaData old ^RecordMetaData new]
  (try (.validate rebuilding-validator old new)
       nil
       (catch MetaDataException e
         (evolution-anomaly (.getMessage e) e old new))))

(defn- validate-unchanged
  [^RecordMetaData old ^RecordMetaData new]
  (try (.validate unchanged-validator old new)
       nil
       (catch MetaDataException e
         (evolution-anomaly (str "meta-data changed without a version bump: "
                                 (.getMessage e))
                            e
                            old
                            new))))

(defn- meta-data-store
  ^FDBMetaDataStore [ctx path]
  (doto (FDBMetaDataStore. ctx (keyspace/path path))
    (.setEvolutionValidator rebuilding-validator)))

(defn- stored
  [^FDBRecordContext ctx ^FDBMetaDataStore ms]
  (.asyncToSync ctx
                FDBStoreTimer$Waits/WAIT_LOAD_META_DATA
                (.getRecordMetaDataAsync ms false)))

(defn load
  [record-db path]
  (try-nom :fdb/meta-data-load
           {:message "FDB meta-data load failed" :path path}
           (.run record-db
                 ^Function
                 (fn [ctx] (stored ctx (meta-data-store ctx path))))))

(defn save
  [record-db path ^RecordMetaData meta-data]
  (try-nom
   :fdb/meta-data-save
   {:message "FDB meta-data save failed" :path path}
   (.run record-db
         ^Function
         (fn [ctx]
           (let [ms (meta-data-store ctx path)
                 old (stored ctx ms)
                 new-version (.getVersion meta-data)]
             (log/info "FDB meta-data stored at"
                       path
                       (some-> old
                               describe))
             (cond
              (nil? old)
              (do (.saveRecordMetaData ms meta-data) :saved)

              (< (.getVersion old) new-version)
              (or (validate-evolution old meta-data)
                  (do (.saveRecordMetaData ms meta-data) :saved))

              (= (.getVersion old) new-version)
              (or (validate-unchanged old meta-data) :current)

              :else
              (error/fail :fdb/meta-data-evolution
                          {:message "stored meta-data is newer than this code's"
                           :old-version (.getVersion old)
                           :new-version new-version})))))))
