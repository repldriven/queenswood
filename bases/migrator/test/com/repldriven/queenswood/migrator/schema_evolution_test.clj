(ns com.repldriven.queenswood.migrator.schema-evolution-test
  "The working tree's record meta-data must be an evolution the migrator
  accepts of the last stable tag's, built here from that tag's
  declaration and protos — the check a changed index, record or version
  meets before it reaches a store holding data."
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]

    [com.repldriven.mono.env.interface :as env]
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]])
  (:import
    (com.apple.foundationdb.record RecordMetaDataOptionsProto)
    (com.google.protobuf DescriptorProtos
                         DescriptorProtos$FileDescriptorSet
                         Descriptors$FileDescriptor
                         ExtensionRegistry)
    (java.io File)
    (java.util.jar JarFile)))

(def ^:private declaration-path
  "components/resources/resources/system/fdb-record-types.yml")

(def ^:private proto-root "components/schema/resources")

(def ^:private schema-proto "schemas/schema.proto")

(defn- sh!
  [& args]
  (let [{:keys [exit out err]} (apply shell/sh args)]
    (is (zero? exit) (str (str/join " " args) "\n" err))
    out))

(defn- repo-root [] (str/trim (sh! "git" "rev-parse" "--show-toplevel")))

(defn- baseline-tag
  [root]
  (str/trim (sh! "git"
                 "-C"
                 root
                 "describe"
                 "--tags" "--match"
                 "stable-*" "--abbrev=0")))

(defn- materialise
  "Writes the tag's declaration and protos under dir, at their repo paths."
  [root tag dir]
  (doseq [path (->> (sh! "git"
                         "-C"
                         root
                         "ls-tree"
                         "-r"
                         "--name-only"
                         tag
                         "--"
                         proto-root
                         declaration-path)
                    str/split-lines
                    (remove str/blank?))]
    (let [out (io/file dir path)]
      (io/make-parents out)
      (spit out (sh! "git" "-C" root "show" (str tag ":" path))))))

(defn- record-layer-protos
  "Extracts the Record Layer's own protos, which the schema imports, from
  its jar into dir, returning the include path."
  [dir]
  (let [include (io/file dir "record-layer")
        jar (JarFile. (io/file (.toURI (.getLocation
                                        (.getCodeSource
                                         (.getProtectionDomain
                                          RecordMetaDataOptionsProto))))))]
    (doseq [entry (enumeration-seq (.entries jar))
            :when (str/ends-with? (.getName entry) ".proto")]
      (let [out (io/file include (.getName entry))]
        (io/make-parents out)
        (with-open [in (.getInputStream jar entry)] (io/copy in out))))
    (str include)))

(def ^:private runtime-descriptors
  {"google/protobuf/descriptor.proto" (DescriptorProtos/getDescriptor)
   "record_metadata_options.proto" (RecordMetaDataOptionsProto/getDescriptor)})

(defn- file-descriptor
  "Compiles the schema under proto-path with protoc and builds its
  descriptor, on the Record Layer's own descriptors for the options the
  schema carries."
  [proto-path include-path]
  (let [set-file (File/createTempFile "fdb-schema" ".pb")
        registry (doto (ExtensionRegistry/newInstance)
                   (RecordMetaDataOptionsProto/registerAllExtensions))]
    (sh! "protoc"
         "--proto_path"
         proto-path
         "--proto_path"
         include-path
         "--include_imports"
         "--descriptor_set_out"
         (str set-file)
         schema-proto)
    (-> (reduce (fn [built proto]
                  (if (contains? built (.getName proto))
                    built
                    (assoc built
                           (.getName proto)
                           (Descriptors$FileDescriptor/buildFrom
                            proto
                            (into-array Descriptors$FileDescriptor
                                        (map built
                                             (.getDependencyList proto)))))))
                runtime-descriptors
                (with-open [in (io/input-stream set-file)]
                  (.getFileList (DescriptorProtos$FileDescriptorSet/parseFrom
                                 in
                                 registry))))
        (get schema-proto))))

(defn- declaration [file] (env/config (str file) :default))

(defn- meta-data
  [root include-path]
  (let [built (fdb/build-meta-data
               (file-descriptor (str (io/file root proto-root))
                                include-path)
               (declaration (io/file root declaration-path)))]
    (is (not (error/anomaly? built)) (pr-str built))
    built))

(deftest working-tree-evolves-from-the-last-stable-tag-test
  (let [root (repo-root)
        tag (baseline-tag root)
        dir (doto (io/file (System/getProperty "java.io.tmpdir")
                           (str "fdb-baseline-" (utility/uuidv7)))
              .mkdirs)
        include-path (record-layer-protos dir)]
    (materialise root tag dir)
    (testing (str "the working tree's record meta-data evolves from " tag)
      (if-not (contains? (declaration (io/file dir declaration-path)) "version")
        (println tag
                 "declares no meta-data version, so there is nothing to"
                 "evolve from")
        (let [old (meta-data (str dir) include-path)
              new (meta-data root include-path)]
          (when-not (or (error/anomaly? old) (error/anomaly? new))
            (let [result (fdb/validate-meta-data-save old new)]
              (is (nil? result)
                  (pr-str (dissoc (error/payload result)
                           :exception
                           :stack-trace))))))))))
