(ns com.repldriven.queenswood.fdb.scan-test
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.fdb.interface :as SUT]

    [com.repldriven.queenswood.test-schema.interface :as test-schema]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]

    [clojure.test :refer [deftest is testing]]))

(def ^:private household "h1")

;; o2 has no toys and o4 has no owner record, so a merge over these two
;; stores meets every combination: both sides, left only, right only.
(def ^:private owners
  [{:household-id household :owner-id "o1" :name "Ann"}
   {:household-id household :owner-id "o2" :name "Ben"}
   {:household-id household :owner-id "o3" :name "Cat"}])

(def ^:private toys
  [{:household-id household :owner-id "o1" :toy-id "t1" :value 1}
   {:household-id household :owner-id "o1" :toy-id "t2" :value 2}
   {:household-id household :owner-id "o1" :toy-id "t3" :value 3}
   {:household-id household :owner-id "o3" :toy-id "t1" :value 4}
   {:household-id household :owner-id "o3" :toy-id "t2" :value 5}
   {:household-id household :owner-id "o4" :toy-id "t1" :value 6}])

(defn- seed
  [config]
  (SUT/transact
   config
   (fn [txn]
     (let [owner-store (SUT/open txn "owners")
           toy-store (SUT/open txn "toys")]
       (doseq [o owners]
         (SUT/save-record owner-store (test-schema/Owner->java o)))
       (doseq [t toys]
         (SUT/save-record toy-store (test-schema/Toy->java t)))))))

(defn- page-keys
  "Pages a store to exhaustion the way a caller would, feeding each
  page's `:after` back in, and returns every key it saw."
  [config store limit]
  (loop [after nil
         acc []]
    (let [page (SUT/transact
                config
                (fn [txn]
                  (SUT/scan-record-entries
                   (SUT/open txn store)
                   (cond-> {:prefix [household] :limit limit}
                           after
                           (assoc :after after)))))]
      (if (error/anomaly? page)
        page
        (let [acc (into acc (map :key) (:entries page))]
          (if-let [next-after (:after page)]
            (recur next-after acc)
            acc))))))

(defn- test-composite-cursor
  [config]
  (testing "paging a store with several rows per key loses none of them"
    ;; A limit of 2 against groups of 3, 2 and 1 puts a page boundary
    ;; inside a group. Cursoring on `owner_id` alone would resume past
    ;; every remaining toy under it, so o1's third toy would vanish.
    (is (= [["o1" "t1"] ["o1" "t2"] ["o1" "t3"]
            ["o3" "t1"] ["o3" "t2"]
            ["o4" "t1"]]
           (page-keys config "toys" 2))))

  (testing "a key with one element past the prefix stays a scalar"
    (is (= ["o1" "o2" "o3"] (page-keys config "owners" 2)))))

(defn- test-merge-outer
  [config]
  (testing "pairs both stores on the shared key, keeping unmatched keys"
    (is (= [["o1" 1 3] ["o2" 1 0] ["o3" 1 2] ["o4" 0 1]]
           (SUT/merge-scan
            config
            {:left {:store "owners" :prefix [household] :limit 2}
             :right {:store "toys" :prefix [household] :limit 2}}
            (fn [acc {:keys [key left right]}]
              (conj acc [key (count left) (count right)]))
            []))))

  (testing "records survive the pairing, not just their counts"
    (let [by-key (SUT/merge-scan
                  config
                  {:left {:store "owners" :prefix [household] :limit 2}
                   :right {:store "toys" :prefix [household] :limit 2}}
                  (fn [acc {:keys [key right]}]
                    (assoc acc
                           key
                           (mapv (comp :value test-schema/pb->Toy) right)))
                  {})]
      (is (= [1 2 3] (get by-key "o1")))
      (is (= [] (get by-key "o2")))
      (is (= [6] (get by-key "o4")))))

  (testing "a page size of one still groups correctly"
    (is (= [["o1" 1 3] ["o2" 1 0] ["o3" 1 2] ["o4" 0 1]]
           (SUT/merge-scan
            config
            {:left {:store "owners" :prefix [household] :limit 1}
             :right {:store "toys" :prefix [household] :limit 1}}
            (fn [acc {:keys [key left right]}]
              (conj acc [key (count left) (count right)]))
            [])))))

(defn- test-merge-short-circuit
  [config]
  (testing "reduced stops the scan and returns what was accumulated"
    (is (= ["o1" "o2"]
           (SUT/merge-scan
            config
            {:left {:store "owners" :prefix [household] :limit 2}
             :right {:store "toys" :prefix [household] :limit 2}}
            (fn [acc {:keys [key]}]
              (let [acc (conj acc key)]
                (if (= 2 (count acc)) (reduced acc) acc)))
            []))))

  (testing "an anomaly from the reducing fn propagates"
    (is (error/anomaly?
         (SUT/merge-scan
          config
          {:left {:store "owners" :prefix [household] :limit 2}
           :right {:store "toys" :prefix [household] :limit 2}}
          (fn [_ _] (error/fail :test/boom "boom"))
          [])))))

(defn- test-merge-empty-side
  [config]
  (testing "an empty right store yields every left key with nothing"
    (is (= [["o1" 1 0] ["o2" 1 0] ["o3" 1 0]]
           (SUT/merge-scan
            config
            {:left {:store "owners" :prefix [household] :limit 2}
             :right {:store "toys" :prefix ["no-such-household"] :limit 2}}
            (fn [acc {:keys [key left right]}]
              (conj acc [key (count left) (count right)]))
            [])))))

(deftest scan-and-merge-test
  (with-test-system
   [sys "classpath:fdb/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :pet-store])}]
     (is (not (error/anomaly? (seed config))))
     (test-composite-cursor config)
     (test-merge-outer config)
     (test-merge-short-circuit config)
     (test-merge-empty-side config))))
