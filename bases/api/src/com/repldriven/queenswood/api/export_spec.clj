(ns com.repldriven.queenswood.api.export-spec
  (:require
    [com.repldriven.queenswood.api.api :as api]

    [com.repldriven.mono.json.interface :as json]

    [clj-yaml.core :as yaml]

    [clojure.java.io :as io]))

(defn -main
  [& [out-path]]
  (let [path (or out-path "docs/openapi.yaml")
        handler (api/app {:interceptors []})
        {:keys [status body]} (handler {:request-method :get
                                        :uri "/openapi.json"})
        body-str (slurp body)
        ;; Fail loudly rather than write the error body as the "spec" —
        ;; a silently-stubbed openapi.yaml is how the broken build went
        ;; unnoticed.
        _ (when (not= 200 status)
            ;; nosemgrep: no-raw-throw
            (throw (ex-info (str "OpenAPI build failed (status " status ")")
                            {:status status :body body-str})))
        spec (-> (json/read-str body-str :key-fn keyword)
                 (update :paths #(into (sorted-map) %)))]
    (io/make-parents path)
    (spit path
          (yaml/generate-string spec
                                :dumper-options
                                {:flow-style :block}))
    (println "Wrote" path)))
