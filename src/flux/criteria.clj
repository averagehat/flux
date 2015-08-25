(ns flux.criteria
  (:refer-clojure :exclude [<= >= > and or])
  (:require [clojure.string :as s])
  (:require [flux.query :as q]))

(def ^{:dynamic true} *junction* "")

(defn- parenthesize [criteria]
  (str "(" criteria ")"))

(defn- join-op [op args]
  (parenthesize (s/join op (flatten args))))

(defn- join-field [f junction field values]
  (str junction (apply f (name field) values)))

(defn- make-criteria [f [maybe-map & values]]
  (let [join-fn (partial join-field f *junction*)]
    (if (map? maybe-map)
      (for [[k & v] maybe-map] (join-fn k v))
      (list (join-fn maybe-map values)))))

(defn- chain-criteria [body]
  (if (= (count body) 1)
    (first body)
    (join-op " AND " body)))

(defn- prefix-keys [prefix opts]
  (when-let [p (name prefix)]
    (for [[k v] opts] {(keyword (str p "." (name k))) v})))

(defn- take-when [pred [x & more :as body]]
  (if (pred x) [x more] [nil body]))

(defn !tag [field tag]
  (str "{!tag=" tag "}" (name field)))

(defn !ex [field exclude]
  (str "{!ex=" exclude "}" (name field)))

(defn facet [field]
  (str "facet.field=" field))

(defn or [& args]
  (join-op " OR " args))

(defn and [& args]
  (join-op " AND " args))

(defn <= [& args]
  (make-criteria #(str %1 ":[* TO " %2 "]") args))

(defn >= [& args]
  (make-criteria #(str %1 ":[" %2 " TO *]") args))

(defn > [& args]
  (make-criteria #(str %1 ":[" %2 " TO *}") args))

(defn has [& args]
  (make-criteria #(str %1 ":\"" %2  "\"") args))

(defn is [& args]
  (make-criteria #(str %1 ":" %2) args))

(defn is-not [& args]
  (binding [*junction* "-"]
    (apply is args)))

(defn any-of [& args]
  (make-criteria #(str %1 ":[" (s/join " " %2) "]") args))

(defn none-of [& args]
  (binding [*junction* "-"]
    (apply any-of args)))

(defn between [& args]
  (make-criteria #(str %1 ":[" (first %2) " TO " (second %2) "]") args))

(defn query [& args]
  (list {:facet.query (chain-criteria args)}))

(defn pivots [& body]
  (let [[maybe-opts args] (take-when map? body)]
    (concat (prefix-keys :facet.pivot maybe-opts)
            (map #(hash-map :facet.pivot (list (s/join "," (map name %)))) args))))

(defn fields [& body]
  (let [[maybe-opts args] (take-when map? body)]
    (concat (prefix-keys :facet maybe-opts)
            (map #(hash-map :facet.field (list (name %))) args))))

(defn with-filter [& body]
  (let [[maybe-query filters] (take-when map? body)]
    (merge-with concat maybe-query {:fq (chain-criteria filters)})))

(defn with-facets [& body]
  (let [[maybe-query facets] (take-when map? body)]
    (apply merge-with concat {:facet true} (dissoc maybe-query :facet) (flatten facets))))

(defn with-options [& body]
  (let [[maybe-query [opts]] (take-when #(not (:rows %)) body)
        rows (:rows opts 100)
        page (:page opts 0)
        sort (:sort opts "score desc")]
    (merge-with concat
                maybe-query
                {:sort sort}
                {:rows rows}
                {:start (* page rows)})))
