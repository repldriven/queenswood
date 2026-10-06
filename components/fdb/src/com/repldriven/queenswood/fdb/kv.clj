(ns com.repldriven.queenswood.fdb.kv
  (:require
    [com.repldriven.mono.error.interface :refer [try-nom]])
  (:import
    (com.apple.foundationdb Database Transaction)
    (java.util.function Function)))

(defn set-str
  [^Database db ^String key ^String value]
  (try-nom
   :fdb/set-str
   {:message "Failed to set value" :key key}
   (.run
    db
    ^Function
    (fn [^Transaction tr] (.set tr (.getBytes key) (.getBytes value)) nil))))

(defn get-str
  [^Database db ^String key]
  (try-nom :fdb/get-str
           {:message "Failed to get value" :key key}
           (.run db
                 ^Function
                 (fn [^Transaction tr]
                   (when-some [^bytes bs (.join (.get tr (.getBytes key)))]
                     (String. bs))))))

(defn set-bytes
  [^Database db ^String key ^bytes value]
  (try-nom
   :fdb/set-bytes
   {:message "Failed to set bytes" :key key}
   (.run db
         ^Function (fn [^Transaction tr] (.set tr (.getBytes key) value) nil))))

(defn get-bytes
  [^Database db ^String key]
  (try-nom :fdb/get-bytes
           {:message "Failed to get bytes" :key key}
           (.run db
                 ^Function
                 (fn [^Transaction tr]
                   (.join (.get tr (.getBytes key)))))))
