(ns com.repldriven.queenswood.audit.core
  (:require
    [com.repldriven.queenswood.audit.store :as store]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]))

(def ^:private crockford "0123456789abcdefghjkmnpqrstvwxyz")

(def ^:private id-prefix "aud.")

(def ^:private id-chars 26)

(def ^:private versionstamp-bytes 12)

(defn- encode
  [^bytes versionstamp]
  (let [n (BigInteger. 1 versionstamp)
        mask (BigInteger/valueOf 31)]
    (str id-prefix
         (apply str
                (map (fn [i]
                       (nth crockford
                            (.intValue (.and (.shiftRight n (int (* 5 i)))
                                             mask))))
                     (range (dec id-chars) -1 -1))))))

(defn- decode
  [id]
  (when (and (string? id)
             (str/starts-with? id id-prefix)
             (= (+ (count id-prefix) id-chars) (count id)))
    (let [digits (map (fn [c] (str/index-of crockford c))
                      (subs id (count id-prefix)))]
      (when (every? some? digits)
        (let [n (reduce (fn [acc d]
                          (.add (.shiftLeft ^BigInteger acc 5)
                                (BigInteger/valueOf d)))
                        BigInteger/ZERO
                        digits)
              raw (.toByteArray n)
              out (byte-array versionstamp-bytes)
              length (min (alength raw) versionstamp-bytes)]
          (System/arraycopy raw
                            (- (alength raw) length)
                            out
                            (- versionstamp-bytes length)
                            length)
          out)))))

(defn record
  [txn entry]
  (when (:created-by entry)
    (let-nom> [details (when (seq (:details entry))
                         (json/write-str (:details entry)))]
      (store/append txn
                    (-> entry
                        (assoc :details details)
                        (update :created-by select-keys [:kind :principal-id])
                        (update :created-at (fnil identity (utility/now)))
                        (->> (into {} (filter (comp some? val)))))))))

(defn- presented
  [{:keys [versionstamp details] :as entry}]
  (let-nom> [details (if details (json/read-str details :key-fn keyword) {})]
    (-> entry
        (dissoc :versionstamp)
        (assoc :audit-entry-id (encode versionstamp) :details details))))

(defn- cursors
  [opts]
  (let [{:keys [after before limit]} opts]
    (utility/assoc-some {:limit limit}
                        :after (decode after)
                        :before (decode before))))

(defn- page
  [found]
  (let [{:keys [entries before after]} found]
    (let-nom> [audit-entries (reduce (fn [audit-entries found-entry]
                                       (let [entry (presented found-entry)]
                                         (if (error/anomaly? entry)
                                           (reduced entry)
                                           (conj audit-entries entry))))
                                     []
                                     entries)]
      {:audit-entries audit-entries
       :before (some-> before
                       encode)
       :after (some-> after
                      encode)})))

(defn list-by-bank
  [txn bank-id opts]
  (let-nom> [found (store/list-by-bank txn
                                       bank-id
                                       (distinct (:kinds opts))
                                       (cursors opts))]
    (page found)))

(defn list-by-subject
  [txn bank-id subject-id opts]
  (let-nom> [found
             (store/list-by-subject txn bank-id subject-id (cursors opts))]
    (page found)))

