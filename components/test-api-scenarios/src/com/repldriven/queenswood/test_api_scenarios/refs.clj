(ns com.repldriven.queenswood.test-api-scenarios.refs
  "Symbolic value resolution inside scenario step data.

  Steps may embed `[:ref :alias :k1 :k2 ...]` markers that the
  runner expands against a captures map. The captures map is
  populated by steps that include `:as <alias>`: the alias key in
  the map points to a value (typically the parsed response body)
  that later steps can read into.

  Steps may also embed `[:str x y z ...]` markers that concatenate
  their resolved parts via `clojure.core/str`. Postwalk order
  means inner `[:ref ...]` markers resolve to values before the
  surrounding `[:str ...]` joins them — handy when an API field
  expects a single string (e.g. `creditor-bban`) but the source
  data is split across structured fields."
  (:require
    [clojure.walk :as walk]))

(defn ref?
  [x]
  (and (vector? x) (= :ref (first x))))

(defn- ref-find?
  [x]
  (and (vector? x) (= :ref-find (first x))))

(defn- str-marker?
  [x]
  (and (vector? x) (= :str (first x))))

(defn resolve-ref
  [captures [_ alias & path]]
  (let [bound (get captures alias)]
    (if (seq path) (get-in bound path) bound)))

(defn resolve-ref-find
  "Resolve `[:ref-find alias coll-key match-key match-val & path]` by
  selecting the first item in the captured `alias`'s `coll-key`
  collection whose `match-key` equals `match-val`, then reading `path`
  out of it. Lets scenarios target an account by stable attribute
  (e.g. `:gl-code \"1100\"`) instead of a brittle seed-order index."
  [captures [_ alias coll-key match-key match-val & path]]
  (let [coll (get-in captures [alias coll-key])
        item (first (filter #(= match-val (get % match-key)) coll))]
    (if (seq path) (get-in item path) item)))

(defn resolve-all
  "Walk `form`, replacing every `[:ref ...]` marker with its
  captured value and joining every `[:str ...]` marker with
  `clojure.core/str`. Non-marker data passes through unchanged."
  [captures form]
  (walk/postwalk
   (fn [x]
     (cond
      (ref? x)
      (resolve-ref captures x)

      (ref-find? x)
      (resolve-ref-find captures x)

      (str-marker? x)
      (apply str (rest x))

      :else
      x))
   form))
