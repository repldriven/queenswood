(ns com.repldriven.queenswood.fdb.scan
  (:import
    (com.apple.foundationdb.record EndpointType
                                   ExecuteProperties
                                   ScanProperties
                                   TupleRange)
    (com.apple.foundationdb.record.provider.foundationdb
     FDBStoreTimer$Waits)
    (com.apple.foundationdb.tuple Tuple)))

(defn- record->bytes
  [r]
  (-> r
      .getRecord
      .toByteArray))

(defn- prefix-range
  "Returns a TupleRange scoped to a prefix tuple."
  [prefix-tuple]
  (TupleRange/allOf prefix-tuple))

(defn- cursor-tuple
  "Builds a cursor Tuple from prefix parts and a cursor value. The
  cursor is the whole primary key past the prefix, so a scalar and a
  vector both have to widen the prefix correctly."
  [prefix cursor]
  (let [parts (into (vec prefix)
                    (if (sequential? cursor) cursor [cursor]))]
    (Tuple/from (into-array Object parts))))

(defn- cursor
  "The record's primary key past the prefix.

  It has to be the WHOLE tail, not the single element at `position`.
  An exclusive endpoint runs through `ByteArrayUtil.strinc`, which
  advances past every key sharing those bytes as a prefix — so
  resuming after one element of a longer key skips every remaining
  record under it. A store with several rows per that element would
  lose all but the first at each page boundary, silently.

  A one-element tail is returned as the element itself, so stores
  whose key is unique at that position keep the scalar cursor their
  callers already surface as an API page token."
  [r position]
  (let [pk (.getPrimaryKey r)
        tail (mapv #(.get pk (int %)) (range position (.size pk)))]
    (if (= 1 (count tail)) (first tail) tail)))

(defn scan-entries
  [store {:keys [prefix after before limit order]}]
  (let [descending? (= :desc order)
        ;; In `:desc` the client's cursors invert: "next after X" means
        ;; keys below X, "prev before X" means keys above X.
        low-cursor (if descending? before after)
        high-cursor (if descending? after before)
        ;; Backward when traversal opposes key order: asc paginating back
        ;; from a high cursor, or desc running from the end down.
        reverse-scan? (if descending?
                        (nil? low-cursor)
                        (some? high-cursor))
        prefix-size (count (or prefix []))
        prefix-tuple (when (seq prefix)
                       (Tuple/from (into-array Object prefix)))
        base-range (when prefix-tuple
                     (prefix-range prefix-tuple))
        range (cond
               (and prefix-tuple low-cursor)
               (TupleRange.
                (cursor-tuple prefix low-cursor)
                (.getHigh ^TupleRange base-range)
                EndpointType/RANGE_EXCLUSIVE
                (.getHighEndpoint ^TupleRange
                                  base-range))

               (and prefix-tuple high-cursor)
               (TupleRange.
                (.getLow ^TupleRange base-range)
                (cursor-tuple prefix high-cursor)
                (.getLowEndpoint ^TupleRange
                                 base-range)
                EndpointType/RANGE_EXCLUSIVE)

               prefix-tuple
               base-range

               low-cursor
               (TupleRange.
                (cursor-tuple nil low-cursor)
                nil
                EndpointType/RANGE_EXCLUSIVE
                EndpointType/TREE_END)

               high-cursor
               (TupleRange.
                nil
                (cursor-tuple nil high-cursor)
                EndpointType/TREE_START
                EndpointType/RANGE_EXCLUSIVE)

               :else
               TupleRange/ALL)
        execute-props (-> (ExecuteProperties/newBuilder)
                          (.setReturnedRowLimit (inc limit))
                          .build)
        scan-props (ScanProperties. execute-props reverse-scan?)
        raw (->> (.scanRecords store
                               ^TupleRange range
                               nil
                               ^ScanProperties scan-props)
                 .asList
                 (.asyncToSync
                  (.getContext store)
                  FDBStoreTimer$Waits/WAIT_SCAN_RECORDS)
                 vec)
        more? (> (count raw) limit)
        trimmed (cond-> raw
                        more?
                        (subvec 0 limit))
        ;; Native scan yields low-to-high forward, high-to-low reverse.
        page (if (= reverse-scan? descending?)
               trimmed
               (vec (rseq trimmed)))
        ;; Paging back from `before`, the rows beyond the limit lie
        ;; before the page and the cursor's own row after it; paging
        ;; forward it is the other way round.
        rows-before? (if before more? (some? after))
        rows-after? (if before true more?)]
    {:entries (mapv (fn [r]
                      {:key (cursor r prefix-size)
                       :record (record->bytes r)})
                    page)
     :before (when (and rows-before? (seq page))
               (cursor (first page) prefix-size))
     :after (when (and rows-after? (seq page))
              (cursor (peek page) prefix-size))}))

(defn scan
  [store opts]
  (let [{:keys [entries before after]} (scan-entries store opts)]
    {:records (mapv :record entries) :before before :after after}))
