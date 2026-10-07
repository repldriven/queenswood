(ns com.repldriven.queenswood.fdb.rewrite
  (:require
    [com.repldriven.queenswood.fdb.record :as record]
    [com.repldriven.queenswood.fdb.scan :as scan]
    [com.repldriven.queenswood.fdb.transact :as transact]

    [com.repldriven.mono.error.interface :as error]))

(def ^:private default-page 200)

(defn- rewrite-page
  [txn store-name f after limit]
  (let [store (transact/open txn store-name)
        {:keys [entries] :as page} (scan/scan-entries store
                                                      {:after after
                                                       :limit limit})
        changed (reduce (fn [n {:keys [key] :as entry}]
                          (let [outcome (f txn (:record entry))]
                            (cond
                             (nil? outcome)
                             n

                             (= :delete outcome)
                             (do (apply record/delete
                                        store
                                        (if (sequential? key) key [key]))
                                 (inc n))

                             :else
                             (do (record/save store outcome) (inc n)))))
                        0
                        entries)]
    {:changed changed :after (:after page)}))

(defn rewrite-store
  [config store-name f {:keys [limit] :or {limit default-page}}]
  (loop [after nil
         total 0]
    (let [page (transact/transact
                config
                (fn [txn] (rewrite-page txn store-name f after limit))
                :fdb/rewrite-store
                "Failed to rewrite a page of records")]
      (cond
       (error/anomaly? page)
       page

       (:after page)
       (recur (:after page) (+ total (:changed page)))

       :else
       (+ total (:changed page))))))
