(ns graphql-clj.spec
  (:require [clojure.spec :as s]
            [clojure.string :as str]
            [graphql-clj.visitor :as v]
            [graphql-clj.error :as ge]
            [zip.visit :as zv]
            [graphql-clj.visitor :refer [defnodevisitor]])
  (:import [clojure.lang Compiler$CompilerException]))

(def base-ns "graphql-clj")

(def delimiter "-")

(defn- append-pathlast [path s]
  (conj (butlast path) (str (last path) s)))

(defn- boolean?* ;; TODO remove after clojure 1.9
  "From clojure.future: Return true if x is a Boolean"
  [x] (instance? Boolean x))

(defn- int?* ;; TODO remove after clojure 1.9
  "From clojure.future: Return true if x is a fixed precision integer"
  [x] (or (instance? Long x)
          (instance? Integer x)
          (instance? Short x)
          (instance? Byte x)))

(defn- double?* ;; TODO remove after clojure 1.9
  "From clojure.future: Return true if x is a Double"
  [x] (instance? Double x))

(defn- ??
  "Allow nil as well as a predicate"
  [pred v] (or (nil? v) (pred v)))

(def int??     (partial ?? int?*))
(def double??  (partial ?? double?*))
(def string??  (partial ?? string?))
(def boolean?? (partial ?? boolean?*))
(def id??      (partial ?? string?))

(def default-specs
  {"Int!"     int?*     "Int"     int??
   "Float!"   double?*  "Float"   double??
   "String!"  string?   "String"  string??
   "Boolean!" boolean?* "Boolean" boolean??
   "ID!"      string?   "ID"      string??})

(def default-spec-keywords ;; Register specs for global base / default / scalar types
  (set (mapv (fn [[n pred]] (eval (list 'clojure.spec/def (keyword base-ns n) pred))) default-specs)))

(def directive-specs
  {"include" {"if" "Boolean"}
   "skip"    {"if" "Boolean"}})

(defn directive-spec-name [directive-name arg-name]
  (keyword (str base-ns ".arg.@" (name directive-name)) (name arg-name)))

(doseq [[n args] directive-specs] ;; Register specs for supported directives
  (doseq [[arg spec-name] args]
    (eval (list 'clojure.spec/def (directive-spec-name n arg) (keyword base-ns spec-name)))))

(def default-types
  {"Int"     {:type-name "Int"     :kind :SCALAR}
   "Float"   {:type-name "Float"   :kind :SCALAR}
   "String"  {:type-name "String"  :kind :SCALAR}
   "Boolean" {:type-name "Boolean" :kind :SCALAR}
   "ID"      {:type-name "ID"      :kind :SCALAR}})

(def base-type-names (set (keys default-types)))
(def default-type-names (set (keys default-specs)))

(defn add-required
  ([name] (str name "!"))
  ([namespace name] (keyword namespace (add-required name))))

(defn remove-required
  ([name] (str/replace name #"\!$" ""))
  ([namespace name] (keyword namespace (remove-required name))))

(defn- to-type-name [{:keys [type-name required]}]
  (if (and required (base-type-names (name type-name))) (add-required type-name) (name type-name)))

(defn- spec-namespace [{:keys [schema-hash statement-hash]} path] ;; TODO make schema vs. statement hash decision upstream
  (->> (butlast path) (mapv name) (into [base-ns (or schema-hash statement-hash)]) (str/join ".")))

(defn named-spec
  "Given a schema hash and a path for a type, return a registered spec identifier (namespaced keyword)"
  [s path]
  (cond (some-> path first name default-type-names) (keyword base-ns (name (first path)))
        (keyword? path)                             path
        (or (vector? path) (seq? path))             (keyword (spec-namespace s path) (name (last path)))
        :else                                       (ge/throw-error "Unhandled named-spec case" {:path path})))

(defn- type-names->args [type-names]
  (mapcat #(vector % %) type-names))

(defn- recursive?
  "If pred is a keyword and also appears in the path of ancestor nodes, it's a recursive definition"
  [path pred]
  (and (keyword? pred) ((set path) (name pred))))

(defn- add-recursive-meta [[n l]]
  [n (with-meta l {:recursive true})])

(defn- register-idempotent
  ([n pred] [n (list 'clojure.spec/def n pred)])
  ([s path pred]
   (cond (keyword? (last path)) [(last path)]
         (recursive? path pred) (add-recursive-meta (register-idempotent (named-spec s (map str path)) pred))
         :else                  (register-idempotent (named-spec s (map str path)) pred))))

(defn- field->spec [s {:keys [v/path]}]
  (named-spec s path))

(defn- to-keys [s fields]
  (list 'clojure.spec/keys
        :opt-un (map (partial field->spec s) (remove :required fields))
        :req-un (map (partial field->spec s) (filter :required fields))))

;; Parent and base types

(defmulti ^:private of-type (fn [n _] (:kind n)))

(defmethod ^:private of-type :LIST [{:keys [inner-type]} s]
  (loop [it inner-type]
    (if (:type-name it)
      (named-spec s [(:type-name it)])
      (recur (:inner-type it)))))

(defmethod ^:private of-type :default [{:keys [spec]} _]
  spec)

(defn get-type-node
  "Given a spec, get the corresponding node from the AST"
  [spec s]
  (get-in s [:spec-map spec]))

(defn get-base-type-node                                    ;; TODO this is surprising compared to get-type-node, because it takes a node instead of a spec
  "Given a spec, get the node definition for the corresponding base type"
  [{:keys [spec]} s]
  (let [base-spec* (s/get-spec spec)
        base-spec (if (keyword? base-spec*) base-spec* spec)]
    (if (default-type-names (name base-spec))
      {:node-type :scalar :type-name (name base-spec)}
      (get-type-node base-spec s))))

(defn get-parent-type
  "Given a node and the global state, find the parent type"
  [{:keys [v/parent] :as n} s]
  (if-let [base-parent (get-type-node (of-type parent s) s)]
    (of-type base-parent s)
    (if (and parent (or (:spec parent) (:kind parent)))
      (recur parent s)
      (ge/throw-error "Parent type not found" {:node n}))))

;; Spec for multimethod to add specs to relevant nodes

(defmulti spec-for
  (fn [{:keys [node-type type-name]} _]
    (or node-type type-name)))

(defn- extension-type [{:keys [v/path fields type-implements]} s]
  (let [ext-spec (register-idempotent s (append-pathlast path (str delimiter "EXT")) (to-keys s fields))
        implements-specs (map (partial named-spec s) (or (:type-names type-implements) []))
        type-names (conj implements-specs ext-spec)]
    (register-idempotent s path (cons 'clojure.spec/or (type-names->args type-names)))))


(defn- base-type [{:keys [v/path fields]} s]
  (register-idempotent s path (to-keys s fields)))

(defmethod spec-for :type-definition [{:keys [type-implements v/path] :as type-def} s]
  (when (> (count path) 0)
    (if-not (empty? (:type-names type-implements))
      (extension-type type-def s)
      (base-type type-def s))))

(defn- coll-of
  "Recursively build up a nested collection"
  [{:keys [inner-type required]} s]
  (let [coll-list (list 'clojure.spec/coll-of (if (:type-name inner-type)
                                                (named-spec s [(to-type-name inner-type)])
                                                (coll-of inner-type s)))]
    (if required coll-list (list 'clojure.spec/nilable coll-list))))

(defmethod spec-for :variable-definition [{:keys [variable-name kind] :as n} s]
  (if (= :LIST kind)
    (register-idempotent (dissoc s :schema-hash) ["var" variable-name] (coll-of n s))
    (register-idempotent (dissoc s :schema-hash) ["var" variable-name] (named-spec s [(to-type-name n)]))))

(defmethod spec-for :variable-usage [{:keys [variable-name]} s]
  (named-spec (dissoc s :schema-hash) ["var" variable-name]))

(defmethod spec-for :input-definition [{:keys [type-name fields]} s]
  (register-idempotent s [type-name] (to-keys s fields)))

(defmethod spec-for :union-definition [{:keys [type-name type-names]} s]
  (register-idempotent s [type-name] (cons 'clojure.spec/or (->> type-names
                                                                 (map (comp (partial named-spec s) vector))
                                                                 type-names->args))))

(defmethod spec-for :enum-definition [{:keys [type-name fields]} s]
  (register-idempotent s [type-name] (set (map :name fields))))

(defmethod spec-for :fragment-definition [{:keys [v/path] :as n} s] ;; TODO fragment spec is equivalent to the type condition spec, when it should be a subset of those fields
  (register-idempotent (dissoc s :schema-hash) ["frag" (:name n)] (named-spec s path)))

(defmethod spec-for :inline-fragment [{:keys [v/path]} s]
  [(named-spec s [(last path)])])

(defmethod spec-for :fragment-spread [n s]
  [(named-spec (dissoc s :schema-hash) ["frag" (:name n)])])

(defmethod spec-for :interface-definition [{:keys [type-name fields]} s]
  (register-idempotent s [type-name] (to-keys s fields)))

(defmethod spec-for :list [{:keys [v/path] :as n} s]
  (register-idempotent s path (coll-of n s)))

(defn- register-type-field [{:keys [v/path] :as n} s]
  (if (and (= (count path) 1) (= (:type-name n) (first path)))
    [(named-spec s [(to-type-name n)])]
    (register-idempotent s path (named-spec s [(to-type-name n)]))))

(defmethod spec-for :type-field [{:keys [v/path kind] :as n} s]
  (if (= :LIST kind)
    (register-idempotent s path (coll-of n s))
    (register-type-field n s)))

(defmethod spec-for :input-type-field [{:keys [v/path kind] :as n} s]
  (if (= :LIST kind)
    (register-idempotent s path (coll-of n s))
    (register-type-field n s)))

(defn- safe-parent-node [path s]
  (get-type-node (named-spec s (butlast path)) s))

(defn- resolve-path [path]
  (if-let [t (some-> path second meta :type-name)]
    (into [t] (rest (rest path)))
    path))

(defmethod spec-for :field [{:keys [v/path v/parent]} s]
  (let [path (resolve-path path)
        base-spec** (:spec parent)
        base-spec* (s/get-spec base-spec**)
        base-spec (if (keyword? base-spec*) base-spec* base-spec**)
        parent-node (get-type-node base-spec s)
        {:keys [type-name inner-type]} parent-node
        parent-type-name (if inner-type (:type-name inner-type) type-name)]
    [(named-spec s (cond (= :inline-fragment (:node-type parent)) [(last (butlast path)) (last path)]
                                  inner-type [parent-type-name (last path)]
                                  :else (conj (:v/path parent-node) (last path))))]))

(defmethod spec-for :argument [{:keys [v/path v/parent]} s]
  (let [path (if (> (count path) 3) (resolve-path path) path)]
    (case (:node-type parent)
      :field [(named-spec s (into ["arg"] path))]
      :directive [(directive-spec-name (-> parent :v/path last) (last path))])))

(defmethod spec-for :type-field-argument [{:keys [v/path kind] :as n} s]
  (if (= :LIST kind)
    (register-idempotent s (into ["arg"] path) (coll-of n s))
    (register-idempotent s (into ["arg"] path) (named-spec s [(to-type-name n)]))))

(defmethod spec-for :default [_ _])

(defn- safe-eval [recursive? d]
  (if (or recursive? (not (meta d)))
    (do
      (assert (= (first d) 'clojure.spec/def))               ;; Protect against unexpected statement eval
      (try (eval d) (catch Compiler$CompilerException _ d))) ;; Squashing errors here to provide better error messages in validation
    d))

;; Visitors

(declare define-specs)
(zv/defvisitor define-specs :post [n s]
  (when (seq? n) ;; Top of the tree is a seq
    (some->>
      (some-> s :spec-defs)
      (mapv (partial safe-eval false)) ;; Don't register recursive types on the first pass
      (filter (comp not keyword?))
      (mapv (partial safe-eval true))) ;; If recursive (skipped) or eval failed the first time, try once more to help with order dependencies
    {:state (dissoc s :spec-defs)}))

(defn add-spec* [n s]
  (when-let [[spec-name spec-def] (spec-for n s)]
    (let [updated-n (-> n (assoc :spec spec-name))]
      (cond-> {:node (dissoc updated-n :v/parent)}
              spec-def (assoc :state (-> s
                                         (update :spec-defs #(conj (or % []) spec-def))
                                         (assoc-in [:spec-map spec-name] updated-n)))))))

(declare add-spec)
(v/defmapvisitor add-spec :post [n s]
  (add-spec* n s))

(declare add-spec-pre)
(v/defmapvisitor add-spec-pre :pre [n s]
  (add-spec* n s))

(declare fix-lists)
(defnodevisitor fix-lists :pre :list
  [{:keys [variable-name field-name argument-name v/parent v/parentk] :as n} s]
  (let [parent-type (:node-type parent)]
    (cond (and field-name (= :input-definition parent-type))
          {:node (assoc n :node-type :input-type-field)}

          (and argument-name (= :type-field parent-type))
          {:node (assoc n :node-type :type-field-argument)}

          (and field-name (= :type-definition parent-type))
          {:node (assoc n :node-type :type-field)}

          (and field-name (= :interface-definition parent-type))
          {:node (assoc n :node-type :type-field)}

          (and variable-name (= :variable-definitions parentk))
          {:node (assoc n :node-type :variable-definition)})))
