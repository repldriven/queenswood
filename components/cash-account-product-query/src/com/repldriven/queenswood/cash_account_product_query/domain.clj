(ns com.repldriven.queenswood.cash-account-product-query.domain)

(defn active-version
  "The published version effective on epoch-day `as-of`: of the
  published versions whose `[effective-from, effective-to)` window
  contains `as-of`, the one with the greatest effective-from (then
  version-number). nil if none. A version with no effective-from is
  treated as effective from the beginning of time."
  [{:keys [versions]} as-of]
  (->> versions
       (filter (fn [v]
                 (= :version-status-published (:status v))))
       (filter (fn [{:keys [effective-from effective-to]}]
                 (and (or (nil? effective-from) (<= effective-from as-of))
                      (or (nil? effective-to) (< as-of effective-to)))))
       (sort-by (fn [{:keys [effective-from version-number]}]
                  [(or effective-from Long/MIN_VALUE) version-number]))
       last))
