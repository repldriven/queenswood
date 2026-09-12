(ns com.repldriven.queenswood.test-startup.core
  (:require
    [com.repldriven.mono.env.interface :as env]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.system.interface :as system]

    [clojure.java.io :as io]
    [clojure.string :as str]))

(def top-namespace "com.repldriven.queenswood")

(def config-name "application.yml")

(def config-source (str "classpath:" config-name))

(def profile :default)

(defn project
  []
  (let [url (io/resource config-name)]
    (when (and url (= "file" (.getProtocol url)))
      (-> (io/file (.getPath url))
          .getParentFile
          .getParentFile
          .getName))))

(defn entry-namespace
  [project]
  (symbol
   (str top-namespace "." (str/replace project #"-service$" "") ".main")))

(defn unregistered
  [defs]
  (for [[group components] (:system/defs defs)
        [component-name component] components
        :when (and (map? component) (:system/component-kind component))]
    [group component-name (:system/component-kind component)]))

(defn registered-count
  [defs]
  (->> (:system/defs defs)
       vals
       (mapcat vals)
       (filter :system/start)
       count))

(defn- root-message
  [^Throwable e]
  (let [root (loop [t e]
               (if-let [c (.getCause t)]
                 (recur c)
                 t))]
    (cond-> (str (ex-message e))
            (not= root e)
            (str "; caused by: " (ex-message root)))))

(defn- load-entry
  [main-ns]
  (try (require main-ns)
       main-ns
       (catch Throwable e
         (error/fail :test-startup/load
                     {:message (str main-ns " does not load: " (root-message e))
                      :namespace main-ns
                      :exception e}))))

(defn- build-defs
  [config]
  (try (system/defs config)
       (catch Throwable e
         (error/fail :test-startup/defs
                     {:message (str "system definitions cannot be built: "
                                    (root-message e))
                      :exception e}))))

(defn- all-registered
  [defs]
  (let [missing (unregistered defs)]
    (if (seq missing)
      (error/fail :test-startup/unregistered
                  {:message (str "component-kinds nothing registered: "
                                 (pr-str (vec missing)))
                   :unregistered (vec missing)})
      defs)))

(defn check
  [project]
  (let-nom> [main-ns (load-entry (entry-namespace project))
             config (env/config config-source profile)
             defs (build-defs config)
             _ (all-registered defs)]
    {:namespace main-ns :registered (registered-count defs)}))
