(ns com.repldriven.queenswood.test-api-scenarios.corpus-test
  "Checks over the scenario corpus that need no running system: every
  scenario and fixture validates, every fixture is used, and no
  idempotency key literal is shared between files."
  (:require
    [com.repldriven.queenswood.test-api-scenarios.interface :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.edn :as edn]
    [clojure.test :refer [deftest is testing]]
    [clojure.walk :as walk]))

(deftest every-scenario-validates-test
  (doseq [{:keys [relative]} (SUT/scenario-files)]
    (testing relative
      (let [loaded (SUT/from-resource (SUT/scenario-resource relative))]
        (is (not (error/anomaly? loaded)) (pr-str loaded))))))

(defn- fixture-name
  [relative]
  (keyword (subs relative 0 (- (count relative) (count ".edn")))))

(deftest every-fixture-validates-test
  (doseq [{:keys [relative]} (SUT/fixture-files)]
    (testing relative
      (let [loaded (SUT/fixture (fixture-name relative))]
        (is (not (error/anomaly? loaded)) (pr-str loaded))))))

(defn- fixtures-named
  [file]
  (let [named (volatile! #{})]
    (walk/postwalk (fn [x]
                     (when (and (map? x) (keyword? (:fixture x)))
                       (vswap! named conj (:fixture x)))
                     x)
                   (edn/read-string (slurp file)))
    @named))

(deftest every-fixture-is-used-test
  (let [used (into #{}
                   (mapcat (comp fixtures-named :file))
                   (concat (SUT/scenario-files) (SUT/fixture-files)))]
    (doseq [{:keys [relative]} (SUT/fixture-files)]
      (is (contains? used (fixture-name relative))
          (str relative " is used by no scenario or fixture")))))

(deftest idempotency-keys-are-unique-across-files-test
  ;; The admin principal is shared by the whole boot, so a key literal
  ;; that appears in two files replays the other file's request rather
  ;; than making its own.
  (let [owners (reduce (fn [m {:keys [file relative]}]
                         (reduce
                          (fn [m [_ k]]
                            (update m k (fnil conj (sorted-set)) relative))
                          m
                          (re-seq #"\"(ik-[^\"]+)\"" (slurp file))))
                       {}
                       (SUT/scenario-files))
        reused (into (sorted-map)
                     (filter (fn [[_ files]] (< 1 (count files))) owners))]
    (is (= {} reused)
        "an Idempotency-Key literal shared by two scenario files replays")))
