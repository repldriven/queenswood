(ns com.repldriven.queenswood.zyphe-simulator.schema
  (:require
    [com.repldriven.mono.utility.interface :refer [vname]]))

(defn components-registry
  [vars]
  (reduce (fn [m v] (assoc m (vname v) @v)) {} vars))

(defn examples-registry
  [examples]
  (reduce (fn [m v] (assoc m (vname v) @v)) {} examples))

(def BaxeError
  [:map
   [:code int?]
   [:errorTag string?]
   [:message {:optional true} [:maybe string?]]])
