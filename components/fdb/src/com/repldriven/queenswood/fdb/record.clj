(ns com.repldriven.queenswood.fdb.record
  (:refer-clojure :exclude [load])
  (:import
    (com.apple.foundationdb.record ExecuteProperties
                                   IndexScanType
                                   ScanProperties
                                   TupleRange)
    (com.apple.foundationdb.record.provider.foundationdb
     FDBStoreTimer$Waits
     IndexScanRange)
    (com.apple.foundationdb.record.metadata IndexAggregateFunction
                                            IndexTypes)
    (com.apple.foundationdb.record.query RecordQuery)
    (com.apple.foundationdb.record.query.expressions Query)
    (com.apple.foundationdb.record.util ProtoUtils$DynamicEnum)
    (com.apple.foundationdb.tuple Tuple)
    (com.google.protobuf MessageLite)))

(defn- record->bytes
  [r]
  (-> r
      .getRecord
      .toByteArray))

(defn- ->tuple
  [k]
  (Tuple/from (into-array Object (if (sequential? k) k [k]))))

(defn load
  [store & primary-key-parts]
  (some-> (.loadRecord store (->tuple primary-key-parts))
          record->bytes))

(defn save
  [store ^MessageLite record]
  (.saveRecord store record)
  nil)

(defn delete
  [store & primary-key-parts]
  (.deleteRecord store (->tuple primary-key-parts)))

(defn enum-value
  [store record-type field number]
  (let [value (-> (.getRecordMetaData store)
                  (.getRecordType record-type)
                  .getDescriptor
                  (.findFieldByName field)
                  .getEnumType
                  (.findValueByNumber number))]
    (ProtoUtils$DynamicEnum. (.getNumber value) (.getName value))))

(defn- field-filter
  [[field value]]
  (-> (Query/field field)
      (.equalsValue value)))

(defn- apply-allowed-indexes
  "Constrains the planner to the named index when
  (:index opts) is provided. Returns the builder."
  [builder opts]
  (let [index (:index opts)]
    (cond-> builder
            index
            (.setAllowedIndexes
             ^java.util.List
             (java.util.ArrayList. ^java.util.Collection [index])))))

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
  ([record-type filters opts]
   (-> (RecordQuery/newBuilder)
       (.setRecordType record-type)
       (.setFilter (Query/and
                    ^java.util.List
                    (java.util.ArrayList. (map field-filter filters))))
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
                        [(.equalsValue (Query/field "key") map-key)
                         (.equalsValue (Query/field "value") map-value)])))))
      (apply-allowed-indexes opts)
      .build))

(defn- execute-query
  [store q]
  (->> (.executeQuery store q)
       .asList
       (.asyncToSync (.getContext store)
                     FDBStoreTimer$Waits/WAIT_EXECUTE_QUERY)))

(defn- execute-query-one
  [store q]
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

(defn query-one-compound
  ([store record-type filters]
   (query-one-compound store record-type filters nil))
  ([store record-type filters opts]
   (some-> (execute-query-one store (and-query record-type filters opts))
           first
           record->bytes)))

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

(defn- aggregate-records
  [store index-type index-name key
   {:keys [isolation] :or {isolation :serializable}}]
  (let [index (.getIndex (.getRecordMetaData store) index-name)
        agg-fn (IndexAggregateFunction. index-type
                                        (.getRootExpression index)
                                        index-name)
        result (.asyncToSync
                (.getContext store)
                FDBStoreTimer$Waits/WAIT_SCAN_INDEX_RECORDS
                (.evaluateAggregateFunction
                 store
                 (java.util.Collections/emptyList)
                 agg-fn
                 (TupleRange/allOf (->tuple key))
                 (if (= :snapshot isolation)
                   com.apple.foundationdb.record.IsolationLevel/SNAPSHOT
                   com.apple.foundationdb.record.IsolationLevel/SERIALIZABLE)))]
    (if (nil? result) 0 (.getLong result 0))))

(defn count-groups
  [store index-name prefix]
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
