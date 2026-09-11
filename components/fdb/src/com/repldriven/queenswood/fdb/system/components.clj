(ns com.repldriven.queenswood.fdb.system.components
  (:require
    [com.repldriven.queenswood.fdb.keyspace :as keyspace]
    [com.repldriven.queenswood.fdb.meta-data :as meta-data]

    [com.repldriven.mono.error.interface :as error :refer [nom->> try-nom]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str])
  (:import
    (com.apple.foundationdb FDB)
    (com.apple.foundationdb.record.provider.foundationdb APIVersion
                                                         FDBDatabaseFactory
                                                         FDBMetaDataStore
                                                         FDBRecordStore)
    (java.io File)
    (java.util.concurrent Executors TimeUnit)))

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
;; store
;; ---

(def
  ^{:doc
    "Opens record stores against meta-data built in process from the
  descriptor and the declaration, without persisting it."}
  store
  {:system/start
   (fn [{:system/keys [config instance]}]
     (or instance
         (let [{:keys [descriptor metadata keyspace-prefix]} config
               meta (meta-data/build descriptor metadata)
               store-names (set (keys (get metadata "stores")))]
           (if (error/anomaly? meta)
             meta
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
                        {:keyspace-prefix keyspace-prefix})))))
   :system/config {:descriptor system/required-component
                   :metadata system/required-component
                   :keyspace-prefix nil}
   :system/config-schema [:map
                          [:descriptor string?]
                          [:metadata map?]
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
  persists the record meta-data built from the descriptor and the
  declaration: saved when its version exceeds the stored one and the
  evolution validator accepts it, a no-op when the stored meta-data is
  at the same version and identical, and otherwise a failure to start,
  which is what makes a change without a version bump visible."}
  meta-store
  {:system/start
   (fn [{:system/keys [config instance]}]
     (or
      instance
      (let [{:keys [record-db path descriptor metadata migrate keyspace-prefix]}
            config
            scoped-path (keyspace/scoped keyspace-prefix path)
            file-desc (meta-data/file-descriptor descriptor)
            migrated (when (truthy-flag? migrate)
                       (log/info "FDB meta-store migrating meta-data at:"
                                 scoped-path)
                       (nom->> (meta-data/build descriptor metadata)
                               (meta-data/save record-db scoped-path)))]
        (if (error/anomaly? migrated)
          migrated
          (do (when migrated (log/info "FDB meta-data at" scoped-path migrated))
              (with-meta (fn [ctx store-name]
                           (open-meta-store ctx
                                            (keyspace/path scoped-path)
                                            file-desc
                                            (keyspace/scoped keyspace-prefix
                                                             store-name)))
                         {:keyspace-prefix keyspace-prefix}))))))
   :system/config {:record-db system/required-component
                   :path system/required-component
                   :descriptor system/required-component
                   :keyspace-prefix nil}
   :system/config-schema [:map
                          [:record-db some?]
                          [:path string?]
                          [:descriptor string?]
                          [:metadata {:optional true} [:maybe map?]]
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
