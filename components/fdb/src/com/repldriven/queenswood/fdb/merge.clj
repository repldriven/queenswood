(ns com.repldriven.queenswood.fdb.merge
  (:require
    [com.repldriven.queenswood.fdb.scan :as scan]
    [com.repldriven.queenswood.fdb.transact :as transact]

    [com.repldriven.mono.error.interface :as error]))

(defn- join-key
  "The element two stores are paired on: the first of the key past the
  prefix. A store with one row per key has that element alone as its
  cursor, so the two shapes have to be handled together."
  [entry]
  (let [k (:key entry)]
    (if (sequential? k) (first k) k)))

(defn- fetch
  "One page of a side, in its own transaction. A merge outlives any
  single transaction — FDB caps them at five seconds — so each refill
  opens a new one rather than holding a cursor open across the scan."
  [config {:keys [store prefix limit]} after]
  (transact/transact
   config
   (fn [txn]
     (scan/scan-entries (transact/open txn store)
                        (cond-> {:prefix prefix :limit limit}
                                after
                                (assoc :after after))))
   :fdb/merge-scan
   "Failed to read a page during merge scan"))

(defn- side [spec] {:spec spec :buffer [] :after nil :more? true})

(defn- fill
  "Guarantees the side has a buffered entry unless its store is spent.
  Returns the side, or an anomaly."
  [config {:keys [spec buffer after more?] :as s}]
  (if (or (seq buffer) (not more?))
    s
    (let [page (fetch config spec after)]
      (if (error/anomaly? page)
        page
        (assoc s
               :buffer (vec (:entries page))
               :after (:after page)
               ;; `scan` sets :after only when rows remain beyond the
               ;; page, so its absence is the end of the store.
               :more? (some? (:after page)))))))

(defn- take-group
  "Pulls every entry sharing `k` off the front of a side, refilling as
  it goes so a group split across a page boundary still comes back
  whole. Returns `[side entries]`, or an anomaly."
  [config s k]
  (loop [s s
         acc []]
    (let [s (fill config s)]
      (if (error/anomaly? s)
        s
        (let [head (first (:buffer s))]
          (if (and head (= k (join-key head)))
            (recur (update s :buffer subvec 1) (conj acc head))
            [s acc]))))))

(defn merge-scan
  [config {:keys [left right]} f init]
  (loop [l (side left)
         r (side right)
         acc init]
    (let [step
          (let [l (fill config l)]
            (if (error/anomaly? l)
              l
              (let [r (fill config r)]
                (if (error/anomaly? r)
                  r
                  (let [lk (some-> (first (:buffer l))
                                   join-key)
                        rk (some-> (first (:buffer r))
                                   join-key)]
                    (if (and (nil? lk) (nil? rk))
                      (reduced acc)
                      (let [k (cond (nil? lk)
                                    rk

                                    (nil? rk)
                                    lk

                                    (neg? (compare lk rk))
                                    lk

                                    :else
                                    rk)
                            lres (if (= k lk)
                                   (take-group config l k)
                                   [l []])]
                        (if (error/anomaly? lres)
                          lres
                          (let [rres (if (= k rk)
                                       (take-group config r k)
                                       [r []])]
                            (if (error/anomaly? rres)
                              rres
                              (let [[l lg] lres
                                    [r rg] rres
                                    acc (f acc
                                           {:key k
                                            :left (mapv :record lg)
                                            :right (mapv :record rg)})]
                                (cond
                                 (error/anomaly? acc)
                                 acc

                                 (reduced? acc)
                                 acc

                                 :else
                                 [l r acc]))))))))))))]
      (cond
       (error/anomaly? step)
       step

       (reduced? step)
       @step

       :else
       (recur (first step) (second step) (nth step 2))))))
