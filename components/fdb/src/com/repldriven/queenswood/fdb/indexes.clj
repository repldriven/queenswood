(ns com.repldriven.queenswood.fdb.indexes
  (:require
    [com.repldriven.queenswood.fdb.keyspace :as keyspace]

    [com.repldriven.mono.error.interface :refer [try-nom]]
    [com.repldriven.mono.log.interface :as log])
  (:import
    (com.apple.foundationdb.record RecordMetaData)
    (com.apple.foundationdb.record.metadata Index)
    (com.apple.foundationdb.record.provider.foundationdb FDBDatabase
                                                         FDBRecordContext
                                                         FDBRecordStore
                                                         FDBRecordStore$Builder
                                                         OnlineIndexer)
    (java.util.function Function)))

(defn- store-builder
  ^FDBRecordStore$Builder [^RecordMetaData meta path]
  (-> (FDBRecordStore/newBuilder)
      (.setMetaDataProvider meta)
      (.setKeySpacePath (keyspace/path path))))

(defn- unreadable
  "Opens the store at `path`, which evolves it to `meta` and builds a new
  index inline where the store holds few records, and returns those of
  `record-type`'s indexes the open left unreadable."
  [^FDBDatabase record-db ^RecordMetaData meta path record-type]
  (.run record-db
        ^Function
        (fn [^FDBRecordContext ctx]
          (let [store (-> (store-builder meta path)
                          (.setContext ctx)
                          .createOrOpen)]
            (into []
                  (remove (fn [^Index index] (.isIndexReadable store index)))
                  (.getAllIndexes (.getRecordType meta record-type)))))))

(defn- build
  [^FDBDatabase record-db ^RecordMetaData meta path ^Index index]
  (log/info "FDB building index" (.getName index) "in" path)
  (with-open [indexer (-> (OnlineIndexer/newBuilder)
                          (.setDatabase record-db)
                          (.setRecordStoreBuilder (store-builder meta path))
                          (.setIndex index)
                          .build)]
    (.buildIndex indexer))
  (.getName index))

(defn build-unreadable
  [record-db meta declaration keyspace-prefix]
  (try-nom
   :fdb/index-build
   "FDB index build failed"
   (into {}
         (keep (fn [[store-name {:strs [record-type]}]]
                 (let [path (keyspace/scoped keyspace-prefix store-name)
                       built (mapv
                              (fn [index] (build record-db meta path index))
                              (unreadable record-db meta path record-type))]
                   (when (seq built) [store-name built]))))
         (get declaration "stores"))))
