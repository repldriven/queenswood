(ns com.repldriven.queenswood.test-model.parties
  (:require
    [com.repldriven.queenswood.test-model.state :as state]

    [clojure.test.check.generators :as gen]))

(defn- ni-arg-gen
  [state]
  (let [fresh (state/next-ni-id state)
        existing (vec (mapcat seq (vals (:nis-by-bank state))))]
    (gen/frequency
     (cond-> [[5 (gen/return nil)]
              [3 (gen/return fresh)]]

             (seq existing)
             (conj [2 (gen/elements existing)])))))

(def create-person-party
  {:run? (fn [state] (seq (state/known-banks state)))
   :args (fn [state]
           (gen/tuple (gen/elements (state/known-banks state))
                      (ni-arg-gen state)))
   :next-state
   (fn [state {[bank-id ni-marker] :args}]
     (let [duplicate? (and ni-marker
                           (contains? (get-in state [:nis-by-bank bank-id])
                                      ni-marker))
           fresh-ni? (and ni-marker (= ni-marker (state/next-ni-id state)))
           party-id (state/next-party-id state)]
       (cond->
        state

        true
        (update :next-party-id inc)

        fresh-ni?
        (update :next-ni-id inc)

        (and ni-marker (not duplicate?))
        (update-in [:nis-by-bank bank-id]
                   (fnil conj #{})
                   ni-marker)

        (not duplicate?)
        (-> (assoc-in [:parties party-id]
                      {:bank bank-id
                       :type :person
                       :status :active})
            (update-in [:banks bank-id :parties]
                       (fnil conj [])
                       party-id)))))
   :valid? (fn [state {[bank-id] :args}] (contains? (:banks state) bank-id))})
