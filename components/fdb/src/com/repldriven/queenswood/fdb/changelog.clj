(ns com.repldriven.queenswood.fdb.changelog
  (:import
    (com.apple.foundationdb KeySelector MutationType)
    (com.apple.foundationdb.record.provider.foundationdb
     FDBDatabase
     FDBStoreTimer$Waits)
    (com.apple.foundationdb.subspace Subspace)
    (com.apple.foundationdb.tuple Tuple Versionstamp)
    (java.util.function Function)))

(def
  ^:private
  ^{:doc
    "Leading element of every changelog, sentinel and checkpoint key.
  A storage constant, not a project name — renaming it strands every
  existing record, changelog entry and consumer checkpoint."}
  root
  "mono")

(defn- rooted
  "Builds a Subspace from parts under the shared root, qualified by
  prefix when one is set. A blank prefix must produce byte-identical
  keys to the unqualified form — a changed encoding silently strands
  every existing record, changelog entry and consumer checkpoint."
  [prefix parts]
  (Subspace. (Tuple/from (into-array Object
                                     (if (seq prefix)
                                       (into [prefix root] parts)
                                       (into [root] parts))))))

(defn- changelog-subspace
  "Returns the Subspace for the changelog of store-name. Entries are
  keyed by versionstamp — (commit-version, user-version) — giving a
  globally ordered, append-only log. Scanning from a checkpoint
  forward is an efficient range read with no secondary index needed."
  [prefix store-name]
  ;; changelog-key = [prefix] , root , "changelog" , store-name ,
  ;;                 versionstamp ;
  (rooted prefix ["changelog" store-name]))

(defn- sentinel-key
  "Returns the raw FDB key bytes for the changelog sentinel — a single key
  atomically incremented on every write, suitable for FDB watches."
  [prefix store-name]
  ;; sentinel-key = [prefix] , root , "sentinel" , store-name ;
  (.pack (rooted prefix ["sentinel" store-name])))

(defn- checkpoint-key
  "Returns the raw FDB key bytes for a per-consumer checkpoint — each
  consumer tracks the last versionstamp it processed independently."
  [prefix consumer-id store-name]
  ;; checkpoint-key = [prefix] , root , "checkpoint" , consumer-id ,
  ;;                  store-name ;
  (.pack (rooted prefix ["checkpoint" consumer-id store-name])))

(defn- read-checkpoint
  "Returns the Versionstamp of the last processed changelog entry for
  the given checkpoint key, or nil if no checkpoint exists yet."
  [^FDBDatabase record-db checkpoint-key]
  (.run record-db
        ^Function
        (fn [ctx]
          (some-> (.asyncToSync ctx
                                FDBStoreTimer$Waits/WAIT_LOAD_SYSTEM_KEY
                                (.get (.ensureActive ctx) checkpoint-key))
                  (Versionstamp/fromBytes)))))

(defn- write-checkpoint
  "Stores the raw bytes of vs as the checkpoint at checkpoint-key
  within the given transaction."
  [tr checkpoint-key ^Versionstamp vs]
  (.set tr checkpoint-key (.getBytes vs)))

(defn write
  [store prefix store-name ^String record-id ^bytes changelog-bytes]
  (let [ctx (.getContext store)
        tr (.ensureActive ctx)
        user-ver (.claimLocalVersion ctx)]
    (.mutate tr
             MutationType/SET_VERSIONSTAMPED_KEY
             (.packWithVersionstamp
              (changelog-subspace prefix store-name)
              (Tuple/from (object-array [(Versionstamp/incomplete user-ver)])))
             (.pack (Tuple/from (object-array [record-id changelog-bytes]))))
    (.mutate tr
             MutationType/ADD
             (sentinel-key prefix store-name)
             (byte-array [1 0 0 0 0 0 0 0]))))

(defn- scan
  "Returns a Java List of KeyValues from the changelog for store-name
  that come strictly after from-vs, or all entries when from-vs is nil."
  [ctx prefix store-name from-vs]
  (let [subspace (changelog-subspace prefix store-name)
        begin (if from-vs
                (KeySelector/firstGreaterThan
                 (.pack subspace (Tuple/from (object-array [from-vs]))))
                (KeySelector/firstGreaterOrEqual (.pack subspace)))
        end (KeySelector/firstGreaterOrEqual (-> subspace
                                                 .range
                                                 .end))]
    (.asyncToSync ctx
                  FDBStoreTimer$Waits/WAIT_SCAN_RECORDS
                  (-> (.getRange (.ensureActive ctx) begin end)
                      .asList))))

(defn- deduplicate
  "Keeps only the latest changelog entry per record-id. Since entries
  are ordered by versionstamp, last within each group is the most
  recent write for that record. Extracts record-id from position 0
  of the Tuple value."
  [entries]
  (->> entries
       (group-by (fn [kv] (.getString (Tuple/fromBytes (.getValue kv)) 0)))
       vals
       (map last)))

(defn process
  ([^FDBDatabase record-db consumer-id store-name handler]
   (process record-db consumer-id store-name handler {}))
  ([^FDBDatabase record-db consumer-id store-name handler opts]
   (let [{:keys [deduplicate? keyspace-prefix] :or {deduplicate? true}} opts
         cp-key (checkpoint-key keyspace-prefix consumer-id store-name)]
     (.run record-db
           ^Function
           (fn [ctx]
             (let [tr (.ensureActive ctx)
                   cp (read-checkpoint record-db cp-key)
                   entries (scan ctx keyspace-prefix store-name cp)]
               (when (seq entries)
                 (doseq [kv (cond-> entries
                                    deduplicate?
                                    deduplicate)]
                   (let [tuple (Tuple/fromBytes (.getValue kv))
                         changelog-bytes (.getBytes tuple 1)]
                     (handler ctx changelog-bytes)))
                 (let [subspace (changelog-subspace keyspace-prefix store-name)
                       last-vs (.getVersionstamp
                                (.unpack subspace (.getKey (last entries)))
                                0)]
                   (write-checkpoint tr cp-key last-vs)))
               nil))))))
