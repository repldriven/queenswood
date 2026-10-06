(ns com.repldriven.queenswood.fdb.stamp
  (:refer-clojure :exclude [read])
  (:require
    [com.repldriven.queenswood.fdb.changelog :as changelog])
  (:import
    (com.apple.foundationdb MutationType)
    (com.apple.foundationdb.record.provider.foundationdb
     FDBRecordContext
     FDBStoreTimer$Waits)
    (com.apple.foundationdb.subspace Subspace)
    (com.apple.foundationdb.tuple Tuple Versionstamp)))

(defn- stamp-key
  [prefix stamp-name]
  ;; stamp-key = [prefix] , root , "stamp" , stamp-name ;
  (.pack ^Subspace
         (changelog/rooted prefix ["stamp" stamp-name])))

(defn bump
  [^FDBRecordContext ctx prefix stamp-name]
  (.mutate (.ensureActive ctx)
           MutationType/SET_VERSIONSTAMPED_VALUE
           (stamp-key prefix stamp-name)
           (.packWithVersionstamp
            (Tuple/from (object-array [(Versionstamp/incomplete 0)])))))

(defn read
  [^FDBRecordContext ctx prefix stamp-name]
  (some-> (.asyncToSync ctx
                        FDBStoreTimer$Waits/WAIT_LOAD_SYSTEM_KEY
                        (.get (.snapshot (.ensureActive ctx))
                              (stamp-key prefix stamp-name)))
          (Tuple/fromBytes)
          (.getVersionstamp 0)))
