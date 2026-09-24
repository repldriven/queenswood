(ns com.repldriven.queenswood.build.proto
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.build.api :as b])
  (:import
    [java.util.jar JarFile]))

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

(defn gen-proto
  [{:keys [root] :or {root "."}}]
  (let [proto-path (str root "/resources")
        clj-out (str root "/gen")
        java-out (str root "/target/gen-java")
        class-out (str root "/classes")
        fdb-path (fdb-proto-dir root)
        protos (proto-files proto-path)]
    (when (empty? protos)
      ;; nosemgrep: no-raw-throw
      (throw (ex-info "No .proto files found" {:path proto-path})))
    (run! #(.mkdirs (io/file %)) [clj-out java-out class-out])
    (b/process {:command-args (cond-> ["protoc" "--clojure_out" clj-out
                                       "--java_out" java-out "--proto_path"
                                       proto-path]
                                      fdb-path
                                      (conj "--proto_path" fdb-path)

                                      true
                                      (into protos))})
    (strip-fdb-requires clj-out)
    (b/javac {:src-dirs [java-out]
              :class-dir class-out
              :basis (b/create-basis {:project (str root "/deps.edn")})
              :javac-opts ["-proc:none"]})))
