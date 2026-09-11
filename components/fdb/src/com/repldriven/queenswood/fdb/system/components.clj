(ns com.repldriven.queenswood.fdb.system.components
  (:refer-clojure :exclude [name])
  (:require
    [com.repldriven.queenswood.fdb.check :as check]
    [com.repldriven.queenswood.fdb.keyspace :as keyspace]

    [com.repldriven.mono.error.interface :refer [try-nom]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str])
  (:import
    (com.apple.foundationdb FDB)
    (com.apple.foundationdb.record RecordMetaData)
    (com.apple.foundationdb.record.metadata Index
                                            IndexOptions
                                            IndexTypes
                                            Key$Expressions)
    (com.apple.foundationdb.record.metadata.expressions GroupingKeyExpression
                                                        KeyExpression$FanType)
    (com.apple.foundationdb.record.provider.foundationdb APIVersion
                                                         FDBDatabaseFactory
                                                         FDBMetaDataStore
                                                         FDBRecordStore)
    (java.io File)
    (java.util.concurrent Executors TimeUnit)
    (java.util.function Function)))

;; ---
;; defaults
;; ---

(def
  ^{:doc
    "The FDB API version `db` and `record-db` both default to.
  `FDB/selectAPIVersion` is JVM-global and one-shot, so the two cannot
  disagree within a process — both take this as config rather than one
  hardcoding it. 710 is also the Record Layer's ceiling: its `APIVersion`
  enum stops at `API_VERSION_7_1` and rejects the 730/740 a 7.4 client
  selects happily, so raising it waits on the Record Layer, not on a newer
  client."}
  default-api-version
  710)

(def
  ^{:doc
    "Deadline `record-db` sets for async->sync waits, in milliseconds.
  The Record Layer's own `getWithDeadline` default is 5s, which is too tight
  under concurrent first-access: each `FDBMetaDataStore.<init>` performs a
  directory-layer resolution that serialises on the cluster."}
  default-async-to-sync-timeout-ms
  30000)

;; ---
;; cluster-file-path
;; ---

(def cluster-file-path
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance
                       (let [path (:path config)]
                         (log/info "FDB cluster file path:" path)
                         path)))
   :system/config {:path system/required-component}
   :system/config-schema [:map [:path string?]]
   :system/instance-schema string?})

(def container-cluster-file-path
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance
                       (let [container (:container config)
                             host (.getHost container)
                             port (.getFirstMappedPort container)
                             contents (str "fdb:fdb@" host ":" port)
                             tmp (File/createTempFile "fdb" ".cluster")]
                         (.deleteOnExit tmp)
                         (spit tmp contents)
                         (let [path (.getAbsolutePath tmp)]
                           (log/info "FDB cluster file path:" path)
                           path))))
   :system/config {:container system/required-component}
   :system/config-schema [:map [:container some?]]
   :system/instance-schema string?})

;; ---
;; databases
;; ---

(def db
  {:system/start (fn [{:system/keys [config instance]}]
                   (let [{:keys [cluster-file-path api-version]} config
                         api-version (or api-version default-api-version)]
                     (log/info "FDB database start called, instance:" instance
                               "config:" config)
                     (or instance
                         (try-nom :fdb/create-db
                                  {:message "Failed to create FDB database"
                                   :cluster-file-path cluster-file-path}
                                  (let [fdb (FDB/selectAPIVersion api-version)
                                        db (.open fdb cluster-file-path)]
                                    (log/info
                                     "Opened FDB database with cluster file:"
                                     cluster-file-path)
                                    db)))))
   :system/stop (fn [{:system/keys [instance]}]
                  (when (some? instance)
                    (log/info "Closing FDB database")
                    (.close instance)))
   :system/config {:cluster-file-path system/required-component
                   :api-version default-api-version}
   :system/config-schema [:map
                          [:cluster-file-path string?]
                          [:api-version {:optional true} [:maybe pos-int?]]]
   :system/instance-schema some?})

(def record-db
  {:system/start
   (fn [{:system/keys [config instance]}]
     (or instance
         (try-nom
          :fdb/create-record-db
          {:message (str "Failed to create FDB Record Layer database. If the "
                         "api-version was rejected, the Record Layer supports "
                         "630, 700 and 710 only, regardless of client version")}
          (let [{:keys [cluster-file-path async-to-sync-timeout-ms api-version]}
                config
                timeout-ms (or async-to-sync-timeout-ms
                               default-async-to-sync-timeout-ms)
                api-version (APIVersion/fromVersionNumber
                             (or api-version default-api-version))
                db (.getDatabase
                    (doto (FDBDatabaseFactory/instance)
                      (.setAPIVersion api-version)
                      (.setScheduledExecutor
                       (Executors/newSingleThreadScheduledExecutor)))
                    cluster-file-path)]
            (log/info
             "Opening FDB Record Layer database with async->sync timeout (ms):"
             timeout-ms)
            (.setAsyncToSyncTimeout db timeout-ms TimeUnit/MILLISECONDS)
            db))))
   :system/stop (fn [{:system/keys [instance]}]
                  (when (some? instance)
                    (log/info "Closing FDB Record Layer database")
                    (.close instance)))
   :system/config {:cluster-file-path system/required-component
                   :api-version default-api-version}
   :system/config-schema [:map
                          [:cluster-file-path string?]
                          [:api-version {:optional true} [:maybe pos-int?]]
                          [:async-to-sync-timeout-ms {:optional true}
                           [:maybe pos-int?]]]
   :system/instance-schema some?})

;; ---
;; metadata
;; ---

(defn- set-primary-key
  [b record-type primary-key]
  (when primary-key
    (let [expr (if (= 1 (count primary-key))
                 (Key$Expressions/field (first primary-key))
                 (Key$Expressions/concatenateFields ^java.util.List
                                                    primary-key))]
      (.setPrimaryKey (.getRecordType b record-type) expr))))

(defn- set-primary-keys
  [b record-types]
  (doseq [{:strs [record-type primary-key]} (vals record-types)]
    (set-primary-key b record-type primary-key)))

(defn- key-expression
  [{:strs [field fields fan-out nest]}]
  (cond
   fields
   (Key$Expressions/concatenateFields ^java.util.List fields)

   nest
   (.nest (Key$Expressions/field field
                                 (if fan-out
                                   KeyExpression$FanType/FanOut
                                   KeyExpression$FanType/None))
          (key-expression nest))

   :else
   (Key$Expressions/field field
                          (if fan-out
                            KeyExpression$FanType/FanOut
                            KeyExpression$FanType/None))))

(def ^:private index-type->str {"count" IndexTypes/COUNT "sum" IndexTypes/SUM})

(defn- add-indexes
  [builder record-type indexes]
  (doseq [{:strs [name unique type] :as idx-cfg} indexes]
    (let [expr (key-expression idx-cfg)
          idx-type (get index-type->str type "value")
          ;; COUNT groups by every field (0 grouped columns, count entries
          ;; per group). SUM groups by all but the last field and sums that
          ;; trailing value column (1 grouped column).
          grouped-expr (condp = idx-type
                         IndexTypes/COUNT (GroupingKeyExpression. expr 0)
                         IndexTypes/SUM (GroupingKeyExpression. expr 1)
                         expr)
          opts (if unique
                 IndexOptions/UNIQUE_OPTIONS
                 IndexOptions/EMPTY_OPTIONS)]
      (.addIndex builder
                 record-type
                 (Index. name
                         grouped-expr
                         idx-type
                         opts)))))

(defn- resolve-descriptor
  [class-name]
  (let [clazz (Class/forName class-name)
        method (.getMethod clazz "getDescriptor" (into-array Class []))]
    (.invoke method nil (into-array Object []))))

(defn- union-field-numbers
  [file-desc]
  (into {}
        (map (fn [field] [(.getName (.getMessageType field))
                          (.getNumber field)]))
        (.getFields (.findMessageTypeByName file-desc "RecordTypeUnion"))))

(defn- union-ordered
  "Record-type configs in `RecordTypeUnion` field-number order.
  `addIndex` stamps each index with the builder's running version, so the
  order stores are visited in is what fixes every index's added version,
  and the Record Layer refuses a save that moves one. Declaration order
  cannot supply it: the config reader re-reads the record types as a hash
  map, so a new entry lands wherever its hash falls and shifts every index
  after it. Union field numbers do not move — an existing record type
  keeps its number and a new one takes the next free one — so visiting in
  that order leaves every existing index's version where it was. A record
  type absent from the union sorts last, by name."
  [file-desc record-types]
  (let [numbers (union-field-numbers file-desc)]
    (sort-by (juxt #(get numbers (get % "record-type") Integer/MAX_VALUE)
                   #(get % "record-type"))
             (vals record-types))))

(defn- build-meta-data
  [descriptor record-types]
  (let [file-desc (resolve-descriptor descriptor)
        builder (-> (RecordMetaData/newBuilder)
                    (.setRecords file-desc))]
    (set-primary-keys builder record-types)
    (doseq [{:strs [record-type indexes]} (union-ordered file-desc
                                                         record-types)]
      (add-indexes builder record-type indexes))
    (.build builder)))

;; ---
;; store
;; ---

(def store
  {:system/start
   (fn [{:system/keys [config instance]}]
     (or instance
         (let [{:keys [descriptor record-types keyspace-prefix]} config
               meta (build-meta-data descriptor record-types)
               store-names (set (keys record-types))]
           (with-meta (fn [ctx store-name]
                        (when-not (store-names store-name)
                          ;; nosemgrep: no-raw-throw
                          (throw (ex-info "Unknown record store"
                                          {:store store-name})))
                        (-> (FDBRecordStore/newBuilder)
                            (.setMetaDataProvider meta)
                            (.setContext ctx)
                            (.setKeySpacePath (keyspace/path (keyspace/scoped
                                                              keyspace-prefix
                                                              store-name)))
                            .createOrOpen))
                      {:keyspace-prefix keyspace-prefix}))))
   :system/config {:descriptor system/required-component
                   :record-types system/required-component
                   :keyspace-prefix nil}
   :system/config-schema [:map
                          [:descriptor string?]
                          [:record-types map?]
                          [:keyspace-prefix {:optional true}
                           [:maybe string?]]]
   :system/instance-schema fn?})

;; ---
;; meta-store
;; ---

(defn- open-meta-store
  [ctx ks-path file-desc store-name]
  (let [ms (doto (FDBMetaDataStore. ctx ks-path)
             (.setLocalFileDescriptor file-desc))]
    (-> (FDBRecordStore/newBuilder)
        (.setMetaDataStore ms)
        (.setContext ctx)
        (.setKeySpacePath (keyspace/path store-name))
        .createOrOpen)))

(defn- truthy-flag?
  "Accepts a literal boolean (set inline in test YAML) or the string
  shape `!env FDB_MIGRATE` produces, since an env var cannot carry a
  boolean."
  [v]
  (cond (boolean? v)
        v

        (string? v)
        (contains? #{"true" "1" "yes"} (str/lower-case v))

        :else
        false))

(def
  ^{:doc
    "Opens record stores against meta-data persisted in FDB.
  With `migrate` unset, opens for reads only. With `migrate` set, first
  persists the record meta-data — a save made idempotent by treating the
  Record Layer's \"meta-data version must increase\" rejection as a no-op,
  so a genuine schema upgrade has to bump that version on the builder
  explicitly."}
  meta-store
  {:system/start
   (fn [{:system/keys [config instance]}]
     (or
      instance
      (let [{:keys [record-db path descriptor record-types migrate
                    keyspace-prefix]}
            config
            ks-path (keyspace/path (keyspace/scoped keyspace-prefix path))
            file-desc (resolve-descriptor descriptor)]
        (when (truthy-flag? migrate)
          (log/info "FDB meta-store migrating metadata to:" path)
          (try
            (.run record-db
                  ^Function
                  (fn [ctx]
                    (let [ms (FDBMetaDataStore. ctx ks-path)
                          meta-data (build-meta-data descriptor record-types)]
                      (.saveRecordMetaData ms meta-data))
                    nil))
            (catch Exception e
              (if (check/meta-data-already-current? e)
                (log/info
                 "FDB meta-data already persisted at >= current version; skipping save")
                ;; nosemgrep: no-raw-throw
                (throw e)))))
        (with-meta (fn [ctx store-name]
                     (open-meta-store ctx
                                      ks-path
                                      file-desc
                                      (keyspace/scoped keyspace-prefix
                                                       store-name)))
                   {:keyspace-prefix keyspace-prefix}))))
   :system/config {:record-db system/required-component
                   :path system/required-component
                   :descriptor system/required-component
                   :keyspace-prefix nil}
   :system/config-schema [:map
                          [:record-db some?]
                          [:path string?]
                          [:descriptor string?]
                          [:record-types {:optional true} [:maybe map?]]
                          [:keyspace-prefix {:optional true}
                           [:maybe string?]]
                          [:migrate {:optional true}
                           [:maybe [:or boolean? string?]]]]
   :system/instance-schema fn?})

;; ---
;; keyspace-prefix
;; ---

(def
  ^{:doc
    "Prefix qualifying every FDB key this system writes.
  Unset (the production default), keys stay byte-identical to an unprefixed
  deployment. With `generate` set, mints one per boot so test systems
  sharing a testcontainer FDB don't also share stores and cursors."}
  keyspace-prefix
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance
                       (let [{:keys [value generate]} config]
                         (cond (seq value)
                               value

                               (true? generate)
                               (str (utility/uuidv7))

                               :else
                               nil))))
   :system/config {:value nil :generate nil}
   :system/config-schema [:map
                          [:value {:optional true} [:maybe string?]]
                          [:generate {:optional true} [:maybe boolean?]]]
   :system/instance-schema [:maybe string?]})
