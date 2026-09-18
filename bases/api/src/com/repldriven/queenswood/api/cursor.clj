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

(defn- past?
  "True when id `a` falls after id `b` in a seq sorted `order`, which is
  `:asc` or `:desc`. Ascending, that is the larger id, descending the
  smaller — the one comparison every cursor window turns on."
  [order a b]
  (if (= :desc order) (neg? (compare a b)) (pos? (compare a b))))

(defn paginate
  "Windows a seq already sorted by `id-key` in `order` (`:asc` or
  `:desc`) into one page, under `page[after|before|size]` cursor
  semantics. `after` advances further into the seq from that id and
  `before` retreats toward its head, so `order` is what decides
  whether that means the larger ids or the smaller ones; `size` caps
  the page, clamped by `clamp-size`.

  Returns `{:page items :before id :after id}`, where `:before` and
  `:after` are the raw ids `build-links` turns into cursor links, each
  nil when there is no page on that side."
  [items id-key order {:keys [after before size]}]
  (let [limit (clamp-size size)]
    (cond
     after
     (let [rest-items (drop-while (fn [item]
                                    (not (past? order (id-key item) after)))
                                  items)
           page (vec (take limit rest-items))]
       {:page page
        :before (when (seq page) (id-key (first page)))
        :after (when (> (count rest-items) limit) (id-key (last page)))})

     before
     (let [earlier (take-while (fn [item] (past? order before (id-key item)))
                               items)
           page (vec (take-last limit earlier))]
       {:page page
        :before (when (> (count earlier) limit) (id-key (first page)))
        :after (when (seq page) (id-key (last page)))})

     :else
     (let [page (vec (take limit items))]
       {:page page
        :before nil
        :after (when (> (count items) limit) (id-key (last page)))}))))

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
