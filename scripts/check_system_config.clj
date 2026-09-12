(ns check-system-config
  "Every `system/component-kind` a project's production
  `application.yml` names, against the kinds its own classpath
  registers. Run from a project directory, whose `deps.edn` names the
  entry namespace the bundle requires hang off."
  (:require
    [com.repldriven.mono.env.interface :as env]
    [com.repldriven.mono.system.components :as components]))

(defn -main
  [& _]
  (let [main (get-in (read-string (slurp "deps.edn"))
                     [:aliases :build :exec-args :main])
        _ (require main)
        config (env/config "classpath:application.yml" :default)
        registered (set (keys (methods components/component)))
        named (into (sorted-set)
                    (comp (filter map?)
                          (keep :system/component-kind)
                          (map keyword))
                    (tree-seq coll? seq (:system config)))
        missing (vec (remove registered named))]
    (println (format "%s: %d component-kinds named, %d unregistered"
                     main
                     (count named)
                     (count missing)))
    (when (seq missing) (println "  unregistered:" missing))
    (System/exit (if (seq missing) 1 0))))
