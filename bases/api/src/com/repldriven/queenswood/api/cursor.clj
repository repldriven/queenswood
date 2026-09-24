(ns com.repldriven.queenswood.api.cursor
  (:require
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str])
  (:import
    (java.util Base64)))

(def ^:private prefix "v1:")

(def default-page-size 20)
(def max-page-size 100)

(defn encode
  "Encodes an id as an opaque cursor string. A key of several parts,
  as a store with a compound primary key pages on, is encoded whole."
  [id]
  (let [raw (if (sequential? id) (str/join ":" id) id)]
    (.encodeToString (Base64/getUrlEncoder) (.getBytes (str prefix raw)))))

(defn decode
  "Decodes a cursor string to the id `encode` was given: a string, or a
  vector of the parts of a compound key. Returns nil on an invalid or
  missing cursor."
  [cursor-str]
  (when cursor-str
    (try (let [decoded (String. (.decode (Base64/getUrlDecoder)
                                         ^String cursor-str))]
           (when (.startsWith decoded prefix)
             (let [parts (str/split (subs decoded (count prefix)) #":")]
               (if (next parts) parts (first parts)))))
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

(defn- page-param?
  [param]
  (or (str/starts-with? param "page[") (str/starts-with? param "page%5B")))

(defn request-path
  "The path `request` was made to, with its query string less any `page`
  parameter: the base a paged list's links extend when the list takes
  other query parameters, which the links must carry."
  [request]
  (let [{:keys [uri query-string]} request
        kept (when query-string
               (->> (str/split query-string #"&")
                    (remove page-param?)
                    (str/join "&")))]
    (cond-> uri (seq kept) (str "?" kept))))

(defn page-opts
  "The store options a `page` query asks for: `:limit`, and `:after` or
  `:before` decoded from its cursors."
  [page]
  (let [{:keys [after before size]} page]
    (utility/assoc-some {:limit (clamp-size size)}
                        :after (decode after)
                        :before (decode before))))

(defn window
  "`paginate` driven by a `page` query: its cursors decoded and its size
  clamped. Returns what `paginate` does."
  [items id-key order page]
  (let [{:keys [after before size]} page]
    (paginate items
              id-key
              order
              {:after (decode after) :before (decode before) :size size})))

(defn page-body
  "A list's 200 body: `items`, and `links` to the pages either side where
  the store reported rows there. `before` and `after` are the raw ids of
  the page's first and last rows, as a store scan or `paginate` returns
  them, each nil when nothing lies on that side."
  [path page items {:keys [before after]}]
  (utility/assoc-seq {:items items}
                     :links
                     (build-links path (clamp-size (:size page)) before after)))
