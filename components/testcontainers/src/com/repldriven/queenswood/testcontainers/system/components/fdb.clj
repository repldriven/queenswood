(ns com.repldriven.queenswood.testcontainers.system.components.fdb
  (:require
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.testcontainers.interface :as testcontainers])
  (:import
    (java.time Duration)
    (org.testcontainers.containers GenericContainer)
    (org.testcontainers.containers.wait.strategy Wait)
    (org.testcontainers.images.builder ImageFromDockerfile)))

;; The FDB server version. Must match the client in `components/fdb`, which
;; is built from versions.json — FDB requires a compatible protocol version
;; between client and cluster, so a mismatch fails at connect time with an
;; error naming neither. `version-test` asserts the two agree.
(def fdb-version "7.3.75")
(def default-image-name (str "queenswood/foundationdb:" fdb-version))

;; The port fdbserver binds inside the container, and the only one the
;; image exposes. Docker maps it to a host port of its own choosing.
(def ^:private listen-port 4500)

(def ^:private public-port-file "/var/fdb/public_port")

(def ^:private cluster-file "/usr/local/etc/foundationdb/fdb.cluster")

(defn- fdb-image
  "Build context for the FDB image, loaded from this component's own
  resources. Read from the classpath rather than a path relative to the
  workspace root, so it resolves the same however tests are launched.

  `fdb-version` is passed as a build arg rather than defaulted in the
  Dockerfile, so the server version has one definition here instead of two
  that can drift. The Dockerfile fetches the release for `$(uname -m)`, so
  the image is arch-native on both amd64 and arm64."
  [image-name]
  (-> (ImageFromDockerfile. image-name false)
      (.withFileFromClasspath "Dockerfile" "fdb/Dockerfile")
      (.withFileFromClasspath "fdb.bash" "fdb/fdb.bash")
      (.withBuildArg "FDB_VERSION" fdb-version)))

(defn- exec
  [container & command]
  (.execInContainer container (into-array String command)))

(defn- publish-port!
  "Tells the container which host port it was mapped to.

  fdbserver has to advertise that port rather than the one it binds: a
  client reaching a coordinator is handed the cluster controller's public
  address and re-dials it, so the number has to be valid on the host side
  of the NAT. Docker only assigns it once the container is running, which
  is what makes this a second phase rather than an environment variable."
  [container port]
  (log/info "FDB public port:" port)
  (exec container "sh" "-c" (str "echo " port " > " public-port-file)))

(defn- await-configured
  "Blocks until the database answers, which is the first moment it is
  usable. `configure new single memory` runs inside the container and
  cannot start until the public port lands, so the container having
  started is not on its own a readiness signal."
  [container]
  (loop [attempts-left 120]
    (let [result (exec container
                       "fdbcli"
                       "-C"
                       cluster-file
                       "--exec"
                       "status minimal")]
      (cond
       (zero? (.getExitCode result))
       (log/info "FDB cluster configured")

       (pos? attempts-left)
       (do (Thread/sleep 500) (recur (dec attempts-left)))

       :else
       ;; nosemgrep: no-raw-throw
       (throw (ex-info "FDB cluster never became configured"
                       {:stdout (.getStdout result)
                        :stderr (.getStderr result)}))))))

(defn- start-container
  "Starts FDB on a Docker-assigned host port, in two phases.

  Docker owns the port from the moment the container starts, so there is
  no window between choosing a port and binding it. Finding a free port
  and asking Docker to bind that one has such a window, and containers
  starting concurrently — as they do when test namespaces run in
  parallel — collide in it."
  [config]
  (let [{:keys [image-name]} config
        _ (log/info "Building FDB image:" image-name)
        built-name (.get (fdb-image image-name))
        _ (log/info "Starting FDB container, image:" built-name)
        container (doto (GenericContainer. ^String built-name)
                    (.addExposedPort (int listen-port))
                    (.withStartupTimeout (Duration/ofSeconds 120))
                    ;; The server is not up at this point — the script is
                    ;; waiting to be told which port to advertise.
                    (.waitingFor (Wait/forLogMessage ".*Awaiting public port.*"
                                                     (int 1)))
                    ;; A reused container already holds its port and
                    ;; answers, so the two phases below pass straight
                    ;; through on it; the per-boot keyspace prefix is
                    ;; what keeps the rigs sharing it apart.
                    (.withReuse (testcontainers/reuse? config))
                    (.start))]
    (publish-port! container (.getMappedPort container (int listen-port)))
    (await-configured container)
    container))

;; Reuse finds only a container that has finished starting — port
;; published, cluster configured — so boots racing in parallel would
;; each create one and the rest sit idle for good. A reusable start
;; takes this lock, so the first completes before the next looks.
(def ^:private reusable-start-lock (Object.))

(defn- start-or-find-container
  [config]
  (if (testcontainers/reuse? config)
    (locking reusable-start-lock (start-container config))
    (start-container config)))

(def container
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (start-or-find-container config)))
   :system/stop (fn [{:system/keys [config instance]}]
                  (if (testcontainers/reuse? config)
                    (log/info "Leaving FDB container for the next boot")
                    (do (log/info "Stopping FDB container")
                        (when (some? instance) (.stop instance)))))
   :system/config {:image-name default-image-name :reuse nil}
   :system/config-schema [:map [:image-name string?]
                          [:reuse {:optional true}
                           [:maybe [:or boolean? string?]]]]
   :system/instance-schema some?})
