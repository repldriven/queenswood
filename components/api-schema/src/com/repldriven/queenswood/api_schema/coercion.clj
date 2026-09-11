(ns com.repldriven.queenswood.api-schema.coercion)

(defn enum-coercion
  ([m] (enum-coercion m nil))
  ([m unknown-key]
   (let [decode-m (merge m (update-keys m keyword))
         encode-m (cond-> (zipmap (vals m)
                                  (map keyword (keys m)))
                          unknown-key
                          (assoc unknown-key :unknown))
         json-schema {:type "string"
                      :enum (vec (keys m))}
         decode-fn (fn [v] (get decode-m v v))
         encode-fn (fn [v] (get encode-m v v))
         enum-props {:json-schema json-schema
                     :decode/api decode-fn
                     :encode/api encode-fn}
         enum-values (cond-> (vec (vals m))
                             unknown-key
                             (conj unknown-key))]
     {:decode decode-fn
      :encode encode-fn
      :json-schema json-schema
      :enum-schema
      (fn
        ([] (into [:enum enum-props] enum-values))
        ([extra-props]
         (into [:enum (merge enum-props extra-props)]
               enum-values)))})))
