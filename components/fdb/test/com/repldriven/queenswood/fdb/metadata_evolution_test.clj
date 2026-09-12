(ns ^:eftest/synchronized com.repldriven.queenswood.fdb.metadata-evolution-test
  "The meta-data save the four webhook stores have to survive. The
  declaration carries a version, each index the version it was added and
  last modified at, and the Record Layer refuses a save that moves an
  existing index's added version or changes a key under the same name.
  Against an empty cluster every save is a first save and neither rule
  bites, so this seeds a keyspace with the meta-data as it stood before
  the webhook stores and then runs the real migrate over it.

  The seed is built from a descriptor whose `RecordTypeUnion` has the four
  webhook fields removed: the Record Layer refuses to build meta-data for
  a union record type with no primary key, so the previous state cannot be
  expressed as the current descriptor with four declaration entries
  dropped.

  The bank's descriptor and declaration are named as strings and read off
  the classpath, the way `system/fdb.yml` names them, so this checks the
  bank's own schema without `fdb` requiring the bricks that hold it."
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.fdb.keyspace :as keyspace]
    [com.repldriven.queenswood.fdb.meta-data :as meta-data]
    [com.repldriven.queenswood.fdb.system.components :as components]

    [com.repldriven.mono.env.interface :as env]
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]])
  (:import
    (com.apple.foundationdb.record RecordMetaData)
    (com.apple.foundationdb.record.metadata Index)
    (com.apple.foundationdb.record.provider.foundationdb FDBMetaDataStore)
    (com.google.protobuf Descriptors$FileDescriptor)
    (java.util.function Function)))

(def ^:private descriptor
  "com.repldriven.queenswood.schemas.schemas.SchemaProto")

(def ^:private webhook-stores
  ["webhook-endpoints" "webhook-notifications" "webhook-deliveries"
   "webhook-delivery-attempts"])

(def ^:private webhook-record-types
  #{"WebhookEndpoint" "WebhookNotification" "WebhookDelivery"
    "WebhookDeliveryAttempt"})

(def ^:private webhook-indexes
  #{"WebhookEndpoint_count_by_bank" "WebhookEndpoint_by_idempotency_key"
    "WebhookNotification_by_changelog_event_id"
    "WebhookNotification_by_bank_created" "WebhookDelivery_by_status_due"
    "WebhookDelivery_by_endpoint_created" "WebhookDeliveryAttempt_by_delivery"})

(defn- declaration
  []
  (:record-types (env/config "classpath:fdb/record-types-test.yml" :default)))

(defn- without-webhooks
  "The declaration as it stood before the webhook stores: their entries
  dropped and the version back to the one that preceded the bump adding
  them."
  [{:strs [version stores] :as decl}]
  (assoc decl
         "version" (dec version)
         "stores" (apply dissoc stores webhook-stores)))

(defn- union-without-webhooks
  [^Descriptors$FileDescriptor file-desc]
  (let [builder (.toBuilder (.toProto file-desc))]
    (doseq [i (range (.getMessageTypeCount builder))]
      (let [message (.getMessageTypeBuilder builder i)]
        (when (= "RecordTypeUnion" (.getName message))
          (doseq [j (reverse (range (.getFieldCount message)))]
            (when (str/starts-with? (.getName (.getField message j)) "_Webhook")
              (.removeField message j))))))
    (Descriptors$FileDescriptor/buildFrom
     (.build builder)
     (into-array Descriptors$FileDescriptor (.getDependencies file-desc)))))

(defn- seed-meta-data!
  [record-db path meta]
  (.run record-db
        ^Function
        (fn [ctx]
          (.saveRecordMetaData (FDBMetaDataStore. ctx (keyspace/path path))
                               meta)
          nil)))

(defn- migrate-meta-data!
  [record-db path decl]
  ((:system/start components/meta-store)
   {:system/config {:record-db record-db
                    :path path
                    :descriptor descriptor
                    :metadata decl
                    :migrate true}}))

(defn- stored-meta-data
  [record-db path]
  (.run record-db
        ^Function
        (fn [ctx]
          (.getRecordMetaData (FDBMetaDataStore. ctx (keyspace/path path))))))

(defn- index-versions
  [^RecordMetaData md]
  (into {}
        (map (fn [^Index idx] [(.getName idx) (.getAddedVersion idx)]))
        (.getAllIndexes md)))

(defn- index-names [md] (set (keys (index-versions md))))

(defn- record-type-names
  [^RecordMetaData md]
  (set (keys (.getRecordTypes md))))

(defn- since-versions
  [^RecordMetaData md names]
  (into {}
        (map (fn [name] [name
                         (.getSinceVersion (get (.getRecordTypes md)
                                                name))]))
        names))

(defn- reason
  [anomaly]
  (pr-str (dissoc (error/payload anomaly) :exception :stack-trace)))

(deftest saving-webhook-stores-over-previous-meta-data-test
  (with-test-system
   [sys "classpath:fdb/application-test.yml"]
   (let [record-db (system/instance sys [:fdb :record-db])
         path (str "meta-evolution-" (utility/uuidv7))
         current (declaration)
         previous (without-webhooks current)]
     (testing "the declaration declares the four stores"
       (is (= (count webhook-stores)
              (- (count (get current "stores"))
                 (count (get previous "stores"))))))
     (testing "and bumped its version to carry them"
       (is (= (inc (get previous "version")) (get current "version"))))
     (seed-meta-data! record-db
                      path
                      (#'meta-data/build*
                       (union-without-webhooks (meta-data/file-descriptor
                                                descriptor))
                       previous))
     (let [before (stored-meta-data record-db path)]
       (testing "the keyspace starts on meta-data that predates them"
         (is (empty? (set/intersection webhook-record-types
                                       (record-type-names before)))))
       (let [migrated (migrate-meta-data! record-db path current)]
         (testing "the migrate saved rather than refusing the save"
           (is (not (error/anomaly? migrated)) (reason migrated))))
       (let [after (stored-meta-data record-db path)]
         (testing "the migrate applied, rather than logging already-current"
           (is (< (.getVersion before) (.getVersion after))))
         (testing "the four record types are in the stored meta-data"
           (is (= webhook-record-types
                  (set/intersection webhook-record-types
                                    (record-type-names after)))))
         (testing "so are their indexes"
           (is (= webhook-indexes
                  (set/intersection webhook-indexes (index-names after)))))
         (testing
           "and each new record type carries the version it arrived on,
                  without which the Record Layer refuses the save"
           (is (= (zipmap webhook-record-types (repeat (get current "version")))
                  (since-versions after webhook-record-types))))
         (testing
           "and every index the previous meta-data defined keeps
                  the version it was added on, which is what the Record
                  Layer refuses a save for moving"
           (let [was (index-versions before)]
             (is (= was (select-keys (index-versions after) (keys was)))))))))))
