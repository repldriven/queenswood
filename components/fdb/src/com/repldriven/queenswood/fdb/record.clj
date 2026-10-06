(ns com.repldriven.queenswood.fdb.record
  (:refer-clojure :exclude [load])
  (:import
    (com.apple.foundationdb.record ExecuteProperties
                                   IndexScanType
                                   ScanProperties
                                   TupleRange)
    (com.apple.foundationdb.record.provider.foundationdb
     FDBRecord
     FDBRecordStore
     FDBStoreTimer$Waits
     IndexScanRange)
    (com.apple.foundationdb.record.metadata IndexAggregateFunction
                                            IndexTypes)
    (com.apple.foundationdb.record.query RecordQuery RecordQuery$Builder)
    (com.apple.foundationdb.record.query.expressions Query)
    (com.apple.foundationdb.record.util ProtoUtils$DynamicEnum)
    (com.apple.foundationdb.tuple Tuple)
    (com.google.protobuf Message MessageLite)
    (java.util.concurrent CompletableFuture)))

(defn- record->bytes
  [^FDBRecord r]
  (-> r
      .getRecord
      .toByteArray))

(defn- ->tuple
  ^Tuple [k]
  (Tuple/from (into-array Object (if (sequential? k) k [k]))))

(defn load
  [^FDBRecordStore store & primary-key-parts]
  (some-> (.loadRecord store (->tuple primary-key-parts))
          record->bytes))

;; Every load is issued before any is waited on, so `n` keys cost one
;; round trip in flight together rather than `n` in turn.
(defn load-many
  [^FDBRecordStore store primary-keys]
  (let [futures (mapv (fn [k] (.loadRecordAsync store (->tuple k)))
                      primary-keys)]
    (mapv (fn [^java.util.concurrent.CompletableFuture f]
            (some-> (.join f)
                    record->bytes))
          futures)))

(defn preload-many
  [^FDBRecordStore store primary-keys]
  (run! (fn [k] (.preloadRecordAsync store (->tuple k))) primary-keys))

(defn save
  [^FDBRecordStore store ^MessageLite record]
  (.saveRecord store record)
  nil)

(defn- primary-key
  ^Tuple [^FDBRecordStore store ^Message record]
  (-> (.getRecordMetaData store)
      (.getRecordTypeForDescriptor (.getDescriptorForType record))
      .getPrimaryKey
      (.evaluateMessageSingleton nil record)
      .toTuple))

;; A save reads the record it replaces. Preloading every record first
;; issues those reads together, and each save then finds its record in
;; the store's preload cache.
(defn save-many
  [store-records]
  (let [futures (mapv (fn [[^FDBRecordStore store record]]
                        (.preloadRecordAsync store (primary-key store record)))
                      store-records)]
    (run! (fn [^java.util.concurrent.CompletableFuture f] (.join f)) futures)
    (run! (fn [[store record]] (save store record)) store-records)
    nil))

(defn delete
  [^FDBRecordStore store & primary-key-parts]
  (.deleteRecord store (->tuple primary-key-parts)))

(defn enum-value
  [^FDBRecordStore store record-type field number]
  (let [value (-> (.getRecordMetaData store)
                  (.getRecordType record-type)
                  .getDescriptor
                  (.findFieldByName field)
                  .getEnumType
                  (.findValueByNumber number))]
    (ProtoUtils$DynamicEnum. (.getNumber value) (.getName value))))

(defn- field-filter
  [[field value]]
  (if (sequential? field)
    (let [[outer & inner] field]
      (if (seq inner)
        (.matches (Query/field outer) (field-filter [(vec inner) value]))
        (field-filter [outer value])))
    (-> (Query/field field)
        (.equalsValue value))))

(defn- apply-allowed-indexes
  "Constrains the planner to the named index when
  (:index opts) is provided. Returns the builder."
  ^RecordQuery$Builder [^RecordQuery$Builder builder opts]
  (let [index (:index opts)]
    (cond-> builder
            index
            (.setAllowedIndexes
             ^java.util.List
             (java.util.ArrayList. ^java.util.Collection (vector index))))))

(defn- equals-query
  ([record-type field value]
   (equals-query record-type field value nil))
  ([record-type field value opts]
   (-> (RecordQuery/newBuilder)
       (.setRecordType record-type)
       (.setFilter (field-filter [field value]))
       (apply-allowed-indexes opts)
       .build)))

(defn- and-query
  ([record-type filters]
   (and-query record-type filters nil))
  (^RecordQuery [record-type filters opts]
   (-> (RecordQuery/newBuilder)
       (.setRecordType record-type)
       (.setFilter (Query/and
                    ^java.util.List
                    (java.util.ArrayList. ^java.util.Collection
                                          (map field-filter filters))))
       (apply-allowed-indexes opts)
       .build)))

(defn- map-entry-query
  [record-type map-field map-key map-value opts]
  (-> (RecordQuery/newBuilder)
      (.setRecordType record-type)
      (.setFilter
       (-> (Query/field map-field)
           .oneOfThem
           (.matches
            (Query/and ^java.util.List
                       (java.util.ArrayList.
                        ^java.util.Collection
                        (vector (.equalsValue (Query/field "key") map-key)
                                (.equalsValue (Query/field "value")
                                              map-value)))))))
      (apply-allowed-indexes opts)
      .build))

(defn- execute-query
  [^FDBRecordStore store ^RecordQuery q]
  (->> (.executeQuery store q)
       .asList
       (.asyncToSync (.getContext store)
                     FDBStoreTimer$Waits/WAIT_EXECUTE_QUERY)))

(defn- execute-query-one
  [^FDBRecordStore store ^RecordQuery q]
  (let [props (-> (ExecuteProperties/newBuilder)
                  (.setReturnedRowLimit 1)
                  .build)]
    (->> (.executeQuery store q nil props)
         .asList
         (.asyncToSync (.getContext store)
                       FDBStoreTimer$Waits/WAIT_EXECUTE_QUERY))))

(defn query
  ([store record-type field value]
   (query store record-type field value nil))
  ([store record-type field value opts]
   (mapv record->bytes
         (execute-query store (equals-query record-type field value opts)))))

(defn query-one
  ([store record-type field value]
   (query-one store record-type field value nil))
  ([store record-type field value opts]
   (some-> (execute-query-one store
                              (equals-query record-type field value opts))
           first
           record->bytes)))

(defn query-compound
  ([store record-type filters]
   (query-compound store record-type filters nil))
  ([store record-type filters opts]
   (mapv record->bytes
         (execute-query store (and-query record-type filters opts)))))

(defn query-one-compound
  ([store record-type filters]
   (query-one-compound store record-type filters nil))
  ([store record-type filters opts]
   (some-> (execute-query-one store (and-query record-type filters opts))
           first
           record->bytes)))

(defn query-one-compound-many
  [^FDBRecordStore store record-type filters-list opts]
  (let [props (-> (ExecuteProperties/newBuilder)
                  (.setReturnedRowLimit 1)
                  .build)
        futures (mapv (fn [filters]
                        (.asList (.executeQuery store
                                                (and-query record-type
                                                           filters
                                                           opts)
                                                nil
                                                props)))
                      filters-list)]
    (mapv (fn [^CompletableFuture f]
            (some-> (.asyncToSync (.getContext store)
                                  FDBStoreTimer$Waits/WAIT_EXECUTE_QUERY
                                  f)
                    first
                    record->bytes))
          futures)))

(defn query-by-map-entry
  ([store record-type map-field map-key map-value]
   (query-by-map-entry store record-type map-field map-key map-value nil))
  ([store record-type map-field map-key map-value opts]
   (mapv record->bytes
         (execute-query store
                        (map-entry-query record-type
                                         map-field
                                         map-key
                                         map-value
                                         opts)))))

(def ^:private aggregate-types {:count IndexTypes/COUNT :sum IndexTypes/SUM})

(defn- aggregate-future
  ^CompletableFuture
  [^FDBRecordStore store ^String index-type ^String index-name key isolation]
  (let [index (.getIndex (.getRecordMetaData store) index-name)
        agg-fn (IndexAggregateFunction. index-type
                                        (.getRootExpression index)
                                        index-name)]
    (.evaluateAggregateFunction
     store
     (java.util.Collections/emptyList)
     agg-fn
     (TupleRange/allOf (->tuple key))
     (if (= :snapshot isolation)
       com.apple.foundationdb.record.IsolationLevel/SNAPSHOT
       com.apple.foundationdb.record.IsolationLevel/SERIALIZABLE))))

(defn- aggregate-value
  [^FDBRecordStore store ^CompletableFuture future]
  (let [^Tuple result (.asyncToSync (.getContext store)
                                    FDBStoreTimer$Waits/WAIT_SCAN_INDEX_RECORDS
                                    future)]
    (if (nil? result) 0 (.getLong result 0))))

(defn- aggregate-records
  [store index-type index-name key
   {:keys [isolation] :or {isolation :serializable}}]
  (aggregate-value
   store
   (aggregate-future store index-type index-name key isolation)))

(defn aggregate-many
  [store aggregates {:keys [isolation] :or {isolation :serializable}}]
  (let [futures (mapv (fn [[aggregate index-name key]]
                        (aggregate-future store
                                          (aggregate-types aggregate)
                                          index-name
                                          key
                                          isolation))
                      aggregates)]
    (mapv (fn [f] (aggregate-value store f)) futures)))

(defn count-groups
  [^FDBRecordStore store index-name prefix]
  (let [index (.getIndex (.getRecordMetaData store) index-name)
        bounds (IndexScanRange. IndexScanType/BY_GROUP
                                (TupleRange/allOf (->tuple prefix)))]
    (.asyncToSync (.getContext store)
                  FDBStoreTimer$Waits/WAIT_SCAN_INDEX_RECORDS
                  (.getCount (.scanIndex store
                                         index
                                         bounds
                                         nil
                                         ScanProperties/FORWARD_SCAN)))))

(defn count-records
  ([store index-name key] (count-records store index-name key {}))
  ([store index-name key opts]
   (aggregate-records store IndexTypes/COUNT index-name key opts)))

(defn sum-records
  ([store index-name key] (sum-records store index-name key {}))
  ([store index-name key opts]
   (aggregate-records store IndexTypes/SUM index-name key opts)))
