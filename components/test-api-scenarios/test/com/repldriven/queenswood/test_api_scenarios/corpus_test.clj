(ns com.repldriven.queenswood.test-api-scenarios.corpus-test
  "Checks over the scenario corpus that need no running system: every
  scenario and fixture validates, every fixture is used, no idempotency
  key literal is shared between files, and a PRD's journeys and its
  directory under `journeys/` name the same journeys."
  (:require
    [com.repldriven.queenswood.test-api-scenarios.interface :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
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

(defn- workspace-root
  []
  (loop [dir (.getAbsoluteFile (io/file (System/getProperty "user.dir")))]
    (cond
     (nil? dir)
     nil

     (.exists (io/file dir "workspace.edn"))
     dir

     :else
     (recur (.getParentFile dir)))))

(defn- journey-file
  "The scenario file a PRD journey heading names: `2. Outbound payment
  (happy path)` is `2-outbound-payment-happy-path.edn`."
  [heading]
  (-> heading
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")
      (str ".edn")))

(defn- prd-journeys
  [doc]
  (->> (slurp doc)
       str/split-lines
       (drop-while (fn [line] (not= "## User journeys" line)))
       rest
       (take-while (fn [line] (not (str/starts-with? line "## "))))
       (keep (fn [line] (second (re-matches #"### (\d+\. .+)" line))))
       (map journey-file)
       set))

(defn- journey-scenarios
  "PRD name → the files under `journeys/<prd>/`."
  []
  (reduce (fn [m {:keys [relative]}]
            (let [[top prd file & more] (str/split relative #"/")]
              (if (and (= "journeys" top) file (empty? more))
                (update m prd (fnil conj #{}) file)
                m)))
          {}
          (SUT/scenario-files)))

(deftest every-prd-journey-has-a-scenario-test
  (let [root (workspace-root)]
    (is (some? root) "no workspace.edn above the working directory")
    (doseq [[prd files] (journey-scenarios)]
      (testing prd
        (let [doc (io/file root "docs" "prd" (str prd ".md"))]
          (is (.exists doc) (str "journeys/" prd "/ names no PRD"))
          (when (.exists doc)
            (is
             (= (prd-journeys doc) files)
             (str "journeys/" prd "/ and the PRD's user journeys differ"))))))))
