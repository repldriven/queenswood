(ns com.repldriven.queenswood.fdb.counter
  (:import
    (com.apple.foundationdb MutationType)
    (com.apple.foundationdb.record.provider.foundationdb
     FDBRecordStore
     FDBStoreTimer$Waits)
    (com.apple.foundationdb.tuple Tuple)
    (java.nio ByteBuffer ByteOrder)))

(defn- pack ^bytes [parts] (.pack (Tuple/from (into-array Object parts))))

(defn- long->little-endian
  [n]
  (-> (ByteBuffer/allocate 8)
      (.order ByteOrder/LITTLE_ENDIAN)
      (.putLong n)
      .array))

(defn- little-endian->long
  [bs]
  (-> (ByteBuffer/wrap bs)
      (.order ByteOrder/LITTLE_ENDIAN)
      .getLong))

(defn allocate
  [^FDBRecordStore store prefix & key-parts]
  (let [ctx (.getContext store)
        tr (.ensureActive ctx)
        key (pack (if (seq prefix) (cons prefix key-parts) key-parts))]
    (.mutate tr MutationType/ADD key (long->little-endian 1))
    (->
      (.asyncToSync ctx FDBStoreTimer$Waits/WAIT_LOAD_SYSTEM_KEY (.get tr key))
      little-endian->long)))
