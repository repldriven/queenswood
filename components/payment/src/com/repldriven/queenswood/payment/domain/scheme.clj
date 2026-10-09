(ns com.repldriven.queenswood.payment.domain.scheme)

(def ^:private scheme-types
  "Each scheme's name, as a provider declares it and an adapter is told
  it, by its `SchemeType`."
  {:scheme-type-fps "fps"})

(defn scheme-name
  [scheme-type]
  (scheme-types scheme-type))

(defn scheme-type
  [scheme]
  (some (fn [[scheme-type scheme-name]]
          (when (= scheme scheme-name) scheme-type))
        scheme-types))
