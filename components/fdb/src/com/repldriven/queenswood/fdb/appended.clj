(ns com.repldriven.queenswood.fdb.appended
  (:require
    [com.repldriven.queenswood.fdb.changelog :as changelog]

    [clojure.string :as str])
  (:import
    (java.util Arrays)
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

(defn- vs-key
  ^bytes [^Subspace subspace ^bytes versionstamp]
  (.pack subspace (vs-tuple (Versionstamp/fromBytes versionstamp))))

(defn- just-after
  ^bytes [^bytes k]
  (let [out (byte-array (inc (alength k)))]
    (System/arraycopy k 0 out 0 (alength k))
    out))

(defn- byte-order
  [^bytes a ^bytes b]
  (Arrays/compareUnsigned a b))

(defn scan
  [^FDBRecordContext ctx prefix parts
   {:keys [after before limit order from until]}]
  (let [subspace ^Subspace (changelog/rooted prefix parts)
        whole ^Range (.range subspace)
        descending? (= :desc order)
        low-cursor (if descending? before after)
        high-cursor (if descending? after before)
        reverse-scan? (if descending? (nil? low-cursor) (some? high-cursor))
        begin-key (last (sort byte-order
                              (cond-> [(.begin whole)]
                                      from
                                      (conj (vs-key subspace from))

                                      low-cursor
                                      (conj (just-after (vs-key subspace
                                                                low-cursor))))))
        end-key (first (sort byte-order
                             (cond-> [(.end whole)]
                                     until
                                     (conj (vs-key subspace until))

                                     high-cursor
                                     (conj (vs-key subspace high-cursor)))))
        begin (KeySelector/firstGreaterOrEqual begin-key)
        end (KeySelector/firstGreaterOrEqual end-key)
        raw (if (neg? (byte-order begin-key end-key))
              (vec (.asyncToSync ctx
                                 FDBStoreTimer$Waits/WAIT_SCAN_RECORDS
                                 (-> (.getRange (.snapshot (.ensureActive ctx))
                                                begin
                                                end
                                                (int (inc limit))
                                                (boolean reverse-scan?))
                                     .asList)))
              [])
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

(defn- newer
  [a b]
  (Arrays/compareUnsigned ^bytes (:versionstamp b) ^bytes (:versionstamp a)))

(defn scan-union
  [ctx prefix parts-list {:keys [after before limit] :as opts}]
  (let [opts (assoc opts :order :desc)
        pages (mapv (fn [parts] (scan ctx prefix parts opts)) parts-list)
        union (sort newer (mapcat :entries pages))
        more? (or (> (count union) limit)
                  (some (if before :before :after) pages))
        page (vec (if before
                    (take-last limit union)
                    (take limit union)))
        rows-before? (if before more? (some? after))
        rows-after? (if before true more?)]
    {:entries page
     :before (when (and rows-before? (seq page))
               (:versionstamp (first page)))
     :after (when (and rows-after? (seq page))
              (:versionstamp (peek page)))}))

(defn- vs-number
  ^BigInteger [^bytes versionstamp]
  (BigInteger. 1 versionstamp))

(defn- number-vs
  ^bytes [^BigInteger n]
  (let [raw (.toByteArray n)
        out (byte-array 12)
        length (min (alength raw) 12)]
    (System/arraycopy raw (- (alength raw) length) out (- 12 length) length)
    out))

(defn- first-at-or-after
  [^FDBRecordContext ctx ^Subspace subspace ^BigInteger low ^BigInteger high]
  (let [kv (first (.asyncToSync
                   ctx
                   FDBStoreTimer$Waits/WAIT_SCAN_RECORDS
                   (-> (.getRange (.snapshot (.ensureActive ctx))
                                  (KeySelector/firstGreaterOrEqual
                                   (vs-key subspace (number-vs low)))
                                  (KeySelector/firstGreaterOrEqual
                                   (just-after (vs-key subspace
                                                       (number-vs high))))
                                  (int 1)
                                  false)
                       .asList)))]
    (when kv (entry subspace kv))))

(defn- edge
  [^FDBRecordContext ctx ^Subspace subspace last?]
  (let [whole ^Range (.range subspace)
        kv (first (.asyncToSync ctx
                                FDBStoreTimer$Waits/WAIT_SCAN_RECORDS
                                (-> (.getRange (.snapshot (.ensureActive ctx))
                                               whole
                                               (int 1)
                                               (boolean last?))
                                    .asList)))]
    (when kv (entry subspace kv))))

(defn seek
  [^FDBRecordContext ctx prefix parts reached?]
  (let [subspace ^Subspace (changelog/rooted prefix parts)
        oldest (edge ctx subspace false)
        newest (edge ctx subspace true)]
    (when (and newest (reached? (:value newest)))
      (if (reached? (:value oldest))
        (:versionstamp oldest)
        (loop [low (.add (vs-number (:versionstamp oldest)) BigInteger/ONE)
               high (vs-number (:versionstamp newest))
               found (:versionstamp newest)]
          (if (pos? (.compareTo low high))
            found
            (let [mid (.shiftRight (.add low high) 1)
                  e (first-at-or-after ctx subspace mid high)]
              (cond (nil? e)
                    (recur low (.subtract mid BigInteger/ONE) found)

                    (reached? (:value e))
                    (recur low
                           (.subtract (vs-number (:versionstamp e))
                                      BigInteger/ONE)
                           (:versionstamp e))

                    :else
                    (recur (.add (vs-number (:versionstamp e)) BigInteger/ONE)
                           high
                           found)))))))))

(defn clear-entry
  [^FDBRecordContext ctx prefix parts ^bytes versionstamp]
  (.clear (.ensureActive ctx)
          (.pack ^Subspace (changelog/rooted prefix parts)
                 (vs-tuple (Versionstamp/fromBytes versionstamp)))))

(defn clear-through
  [^FDBRecordContext ctx prefix parts ^bytes versionstamp]
  (let [subspace ^Subspace (changelog/rooted prefix parts)
        last-key (.pack subspace
                        (vs-tuple (Versionstamp/fromBytes versionstamp)))
        end (byte-array (inc (alength last-key)))]
    (System/arraycopy last-key 0 end 0 (alength last-key))
    (.clear (.ensureActive ctx) (.begin (.range subspace)) end)))

(def ^:private crockford "0123456789abcdefghjkmnpqrstvwxyz")

(def ^:private id-chars 26)

(def ^:private versionstamp-bytes 12)

(defn ->id
  [prefix ^bytes versionstamp]
  (let [n (BigInteger. 1 versionstamp)
        mask (BigInteger/valueOf 31)]
    (str prefix
         (apply str
                (map (fn [i]
                       (nth crockford
                            (.intValue (.and (.shiftRight n (int (* 5 i)))
                                             mask))))
                     (range (dec id-chars) -1 -1))))))

(defn ->versionstamp
  [prefix id]
  (when (and (string? id)
             (str/starts-with? id prefix)
             (= (+ (count prefix) id-chars) (count id)))
    (let [digits (map (fn [c] (str/index-of crockford c))
                      (subs id (count prefix)))]
      (when (every? some? digits)
        (let [n (reduce (fn [acc d]
                          (.add (.shiftLeft ^BigInteger acc 5)
                                (BigInteger/valueOf d)))
                        BigInteger/ZERO
                        digits)
              raw (.toByteArray n)
              out (byte-array versionstamp-bytes)
              length (min (alength raw) versionstamp-bytes)]
          (System/arraycopy raw
                            (- (alength raw) length)
                            out
                            (- versionstamp-bytes length)
                            length)
          out)))))
