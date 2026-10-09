(ns com.repldriven.queenswood.build.proto
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.build.api :as b])
  (:import
    [com.google.protobuf DescriptorProtos$DescriptorProto
     DescriptorProtos$FieldDescriptorProto$Label
     DescriptorProtos$FileDescriptorSet]
    [java.nio.file Files Path]
    [java.util.jar JarFile]
    [java.util.regex Pattern]))

(defn- proto-files
  [proto-dir]
  (->> (file-seq (io/file proto-dir))
       (filter #(str/ends-with? (.getName %) ".proto"))
       (map #(.getPath %))))

(defn- extract-protos-from-jar
  "Extract .proto files from a JAR to target-dir.
  Returns target-dir when protos were extracted, nil
  otherwise."
  [jar-path target-dir]
  (let [jar (JarFile. (io/file jar-path))
        entries (enumeration-seq (.entries jar))
        protos (filter #(str/ends-with? (.getName %) ".proto") entries)]
    (when (seq protos)
      (doseq [entry protos]
        (let [out-file (io/file target-dir (.getName entry))]
          (.mkdirs (.getParentFile out-file))
          (with-open [in (.getInputStream jar entry)] (io/copy in out-file))))
      target-dir)))

(defn- fdb-proto-dir
  "Resolve the fdb-record-layer-core JAR, extract its .proto
  files into a temp directory, and return the path."
  [root]
  (let [basis (b/create-basis {:project (str root "/deps.edn")
                               :aliases [:build]})
        cp (:classpath-roots basis)
        jar (first (filter #(str/includes? % "fdb-record-layer-core") cp))
        target (str root "/target/fdb-protos")]
    (when-not jar
      ;; nosemgrep: no-raw-throw
      (throw (ex-info "fdb-record-layer-core JAR not found" {:classpath cp})))
    (extract-protos-from-jar jar target)))

(defn- strip-fdb-requires
  "Remove spurious com.apple.foundationdb requires from
  generated Clojure files. protoc-gen-clojure emits requires
  for imported proto packages, but the FDB options proto has
  no Clojure counterpart."
  [clj-out]
  (doseq [f (->> (file-seq (io/file clj-out))
                 (filter #(str/ends-with? (.getName %) ".cljc")))]
    (let [content (slurp f)
          stripped (str/replace content
                                #"\n\s+\[com\.apple\.foundationdb\.[^\]]*\]"
                                "")]
      (when (not= content stripped) (spit f stripped)))))

(defn- message-required
  "Map of each message's full name, nested messages included, to the
  numbers of its required fields."
  [prefix ^DescriptorProtos$DescriptorProto message]
  (let [full-name (str prefix "." (.getName message))]
    (into {full-name
           (into #{}
                 (comp
                  (filter
                   (fn [field]
                     (=
                      DescriptorProtos$FieldDescriptorProto$Label/LABEL_REQUIRED
                      (.getLabel field))))
                  (map (fn [field] (.getNumber field))))
                 (.getFieldList message))}
          (mapcat (fn [nested] (message-required full-name nested)))
          (.getNestedTypeList message))))

(defn- required-fields
  "Map of message full name to required field numbers, read from the
  descriptor set protoc wrote."
  [descriptor-set]
  (let [files (.getFileList (DescriptorProtos$FileDescriptorSet/parseFrom
                             (Files/readAllBytes (Path/of descriptor-set
                                                          (into-array
                                                           String
                                                           [])))))]
    (into {}
          (mapcat (fn [file]
                    (mapcat (fn [message]
                              (message-required (.getPackage file) message))
                     (.getMessageTypeList file))))
          files)))

(def ^:private record-block
  "A generated message: its `defrecord`, up to the next one."
  #"(?s)\(defrecord (\S+)-record .*?(?=\(defrecord |\z)")

(def ^:private optimized-write
  "A field's write in a generated `serialize`, which skips the type's
  default value."
  #"\((\S+) (\d+)\s+\{:optimize true\} \((:\S+) this\) os\)")

(defn- write-present
  "`block` with each required field written whenever it is non-nil, a
  zero, false or empty string included, and the keys it rewrote."
  [block required]
  (let [keys (atom #{})
        block (str/replace block
                           optimized-write
                           (fn [[write writer tag k]]
                             (if (contains? required (parse-long tag))
                               (do (swap! keys conj (keyword (subs k 1)))
                                   (str "(when-some [v ("
                                        k
                                        " this)] ("
                                        writer
                                        " "
                                        tag
                                        " {:optimize false} v os))"))
                               write)))]
    [block @keys]))

(defn- dissoc-edn
  [edn ks]
  ;; nosemgrep: no-edn-serialization — generated source
  (pr-str (apply dissoc (edn/read-string edn) ks)))

(defn- drop-defaults
  "`content` with `ks` removed from the `<record>-defaults` map, so a
  required field the caller left out stays nil and is not written."
  [content record ks]
  (let [pattern (re-pattern (str "\\(def "
                                 (Pattern/quote
                                  (str record "-defaults"))
                                 " (\\{[^\\n]*\\})\\)"))]
    (str/replace content
                 pattern
                 (fn [[_ defaults]]
                   (str "(def "
                        record
                        "-defaults "
                        (dissoc-edn defaults ks)
                        ")")))))

(defn- write-required-fields
  "Rewrites the generated `serialize` of every message so a required
  field is written whenever it is set. protoc-gen-clojure writes every
  field the proto3 way, leaving a zero, false or empty string off the
  wire, so a required field holding one fails the Java parse."
  [clj-out descriptor-set]
  (let [required (required-fields descriptor-set)]
    (doseq [f (->> (file-seq (io/file clj-out))
                   (filter #(str/ends-with? (.getName %) ".cljc")))]
      (let [content (slurp f)
            rewritten
            (reduce
             (fn [content [block record]]
               (let [full-name (second (re-find
                                        #"\(gettype \[this\]\s+\"([^\"]+)\""
                                        block))
                     [new-block ks] (write-present
                                     block
                                     (get required full-name #{}))]
                 (if (seq ks)
                   (-> content
                       (str/replace block new-block)
                       (drop-defaults record ks))
                   content)))
             content
             (re-seq record-block content))]
        (when (not= content rewritten) (spit f rewritten))))))

(defn gen-proto
  [{:keys [root] :or {root "."}}]
  (let [proto-path (str root "/resources")
        clj-out (str root "/gen")
        java-out (str root "/target/gen-java")
        class-out (str root "/classes")
        fdb-path (fdb-proto-dir root)
        descriptor-set (str root "/target/descriptors.pb")
        protos (proto-files proto-path)]
    (when (empty? protos)
      ;; nosemgrep: no-raw-throw
      (throw (ex-info "No .proto files found" {:path proto-path})))
    (run! (fn [dir] (b/delete {:path dir})) [java-out class-out])
    (run! (fn [f] (io/delete-file f))
          (filter (fn [f] (str/ends-with? (.getName f) ".cljc"))
                  (file-seq (io/file clj-out))))
    (run! #(.mkdirs (io/file %)) [clj-out java-out class-out])
    (b/process {:command-args (cond-> ["protoc" "--clojure_out" clj-out
                                       "--java_out" java-out
                                       "--descriptor_set_out" descriptor-set
                                       "--proto_path" proto-path]
                                      fdb-path
                                      (conj "--proto_path" fdb-path)

                                      true
                                      (into protos))})
    (strip-fdb-requires clj-out)
    (write-required-fields clj-out descriptor-set)
    (b/javac {:src-dirs [java-out]
              :class-dir class-out
              :basis (b/create-basis {:project (str root "/deps.edn")})
              :javac-opts ["-proc:none"]})))
