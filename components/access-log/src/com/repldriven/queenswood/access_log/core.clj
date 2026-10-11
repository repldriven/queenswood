(ns com.repldriven.queenswood.access-log.core
  (:require
    [com.repldriven.queenswood.access-log.store :as store]
    [com.repldriven.queenswood.access-log.writer :as writer]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private client-id-chars
  "The most of an id the client chose that an entry keeps."
  255)

(defn- capped
  [s]
  (when s (subs s 0 (min client-id-chars (count s)))))

(defn record
  [writer entry]
  (writer/offer writer
                (-> entry
                    (utility/assoc-some :idempotency-key
                                        (capped (:idempotency-key entry))
                                        :correlation-id
                                        (capped (:correlation-id entry)))
                    (->> (into {} (filter (comp some? val)))))))

(defn- presented
  [{:keys [versionstamp] :as entry}]
  (-> entry
      (dissoc :versionstamp)
      (assoc :access-entry-id (store/entry-id versionstamp))))

(defn list-by-bank
  [txn bank-id opts]
  (let [{:keys [ref tags limit after before]} opts]
    (let-nom> [found (store/list-by-bank txn
                                         bank-id
                                         ref
                                         tags
                                         (utility/assoc-some
                                          {:limit limit}
                                          :after (store/entry-versionstamp
                                                  after)
                                          :before (store/entry-versionstamp
                                                   before)))]
      {:access-entries (mapv presented (:entries found))
       :before (some-> (:before found)
                       store/entry-id)
       :after (some-> (:after found)
                      store/entry-id)})))

(defn delete-before
  [txn bank-id before limit]
  (store/delete-before txn bank-id before limit))
