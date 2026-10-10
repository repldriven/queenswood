(ns com.repldriven.queenswood.fdb.appended
  (:require
    [com.repldriven.queenswood.fdb.changelog :as changelog])
  (:import
    (com.apple.foundationdb KeySelector KeyValue MutationType Range)
    (com.apple.foundationdb.record.provider.foundationdb
     FDBRecordContext
     FDBStoreTimer$Waits)
    (com.apple.foundationdb.subspace Subspace)
    (com.apple.foundationdb.tuple Tuple Versionstamp)))

(defn- vs-tuple
  ^Tuple [^Versionstamp vs]
  (Tuple/from (object-array [vs])))

(defn append
  [^FDBRecordContext ctx prefix parts-list ^bytes value]
  (let [tr (.ensureActive ctx)
        vs (Versionstamp/incomplete (.claimLocalVersion ctx))]
    (doseq [[i parts] (map-indexed vector parts-list)]
      (.mutate tr
               MutationType/SET_VERSIONSTAMPED_KEY
               (.packWithVersionstamp ^Subspace (changelog/rooted prefix parts)
                                      (vs-tuple vs))
               (if (zero? i) value (byte-array 0))))))

(defn load-entry
  [^FDBRecordContext ctx prefix parts ^bytes versionstamp]
  (.asyncToSync ctx
                FDBStoreTimer$Waits/WAIT_LOAD_SYSTEM_KEY
                (.get (.snapshot (.ensureActive ctx))
                      (.pack ^Subspace (changelog/rooted prefix parts)
                             (vs-tuple (Versionstamp/fromBytes
                                        versionstamp))))))

(defn- entry
  [^Subspace subspace ^KeyValue kv]
  {:versionstamp (.getBytes (.getVersionstamp (.unpack subspace (.getKey kv))
                                              0))
   :value (.getValue kv)})

(defn scan
  [^FDBRecordContext ctx prefix parts {:keys [after before limit order]}]
  (let [subspace ^Subspace (changelog/rooted prefix parts)
        whole ^Range (.range subspace)
        descending? (= :desc order)
        low-cursor (if descending? before after)
        high-cursor (if descending? after before)
        reverse-scan? (if descending? (nil? low-cursor) (some? high-cursor))
        begin (if low-cursor
                (KeySelector/firstGreaterThan
                 (.pack subspace
                        (vs-tuple (Versionstamp/fromBytes low-cursor))))
                (KeySelector/firstGreaterOrEqual (.begin whole)))
        end (if high-cursor
              (KeySelector/firstGreaterOrEqual
               (.pack subspace (vs-tuple (Versionstamp/fromBytes high-cursor))))
              (KeySelector/firstGreaterOrEqual (.end whole)))
        raw (vec (.asyncToSync ctx
                               FDBStoreTimer$Waits/WAIT_SCAN_RECORDS
                               (-> (.getRange (.snapshot (.ensureActive ctx))
                                              begin
                                              end
                                              (int (inc limit))
                                              (boolean reverse-scan?))
                                   .asList)))
        more? (> (count raw) limit)
        trimmed (cond-> raw
                        more?
                        (subvec 0 limit))
        page (mapv (partial entry subspace)
                   (if (= reverse-scan? descending?) trimmed (rseq trimmed)))
        rows-before? (if before more? (some? after))
        rows-after? (if before true more?)]
    {:entries page
     :before (when (and rows-before? (seq page))
               (:versionstamp (first page)))
     :after (when (and rows-after? (seq page))
              (:versionstamp (peek page)))}))
