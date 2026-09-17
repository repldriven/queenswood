(ns com.repldriven.queenswood.api.cursor
  (:require
    [clojure.string :as str])
  (:import
    (java.util Base64)))

(def ^:private prefix "v1:")

(def default-page-size 20)
(def max-page-size 100)

(defn encode
  "Encodes an id as an opaque cursor string."
  [id]
  (.encodeToString (Base64/getUrlEncoder) (.getBytes (str prefix id))))

(defn decode
  "Decodes a cursor string to an id. Returns nil on
  invalid or missing cursor."
  [cursor-str]
  (when cursor-str
    (try (let [decoded (String. (.decode (Base64/getUrlDecoder)
                                         ^String cursor-str))]
           (when (.startsWith decoded prefix) (subs decoded (count prefix))))
         (catch IllegalArgumentException _ nil))))

(defn clamp-size
  "Clamps a requested page size to `[1, max-page-size]`, defaulting to
  `default-page-size` when nil. The `PageQuery` malli schema already
  enforces this range at the API boundary; the bounds here are
  belt-and-suspenders for callers that bypass validation."
  [n]
  (cond (nil? n)
        default-page-size
        (< n 1)
        1
        (> n max-page-size)
        max-page-size
        :else
        n))

(defn build-links
  "Builds a `:next` / `:prev` HATEOAS links map for a cursor-paginated
  list endpoint. `base` is the resource path (e.g.
  `\"/v1/cash-accounts\"`), and may carry a query of its own (e.g.
  `\"/v1/payments/inbound?status=held\"`), which the page parameters
  follow; `before-id` / `after-id` are raw ids that will be
  cursor-encoded into the emitted URLs. Either id may be nil to omit
  the corresponding link."
  [base size before-id after-id]
  (let [separator (if (str/includes? base "?") "&" "?")]
    (cond-> {}
            after-id
            (assoc :next
                   (str base
                        separator
                        "page[after]=" (encode after-id)
                        "&page[size]=" size))
            before-id
            (assoc :prev
                   (str base
                        separator
                        "page[before]=" (encode before-id)
                        "&page[size]=" size)))))
