(ns graphql-clj.executor
  (:require [graphql-clj.parser :as parser]
            [graphql-clj.type :as type]
            [graphql-clj.resolver :as resolver]
            [graphql-clj.error :as gerror]
            [clojure.set :as set]
            [graphql-clj.validator :as validator]))

(defn- update-arguments-fn
  "Update argument value if argument defined as variable and variable does exist in `variables`."
  [variables]
  (fn [result {:keys [argument-name value variable-name] :as argument}]
    (cond
      ;; Argument has value
      (contains? argument :value) (assoc result argument-name value) ; Do not use value directly, since argument value could be null
      ;; Argument has value from variable
      (and variable-name (contains? variables variable-name)) (assoc result argument-name (get variables variable-name))
      :default result)))

(defn- build-arguments
  "Build arguments for field, only process provided arguments.
   Not provided arguments will be taken care by default arguments value after building arguments.
   About nulltype discussion see https://github.com/facebook/graphql/pull/83."
  [selection variables]
  (reduce (update-arguments-fn variables) {} (:arguments selection)))

(defn- get-selection-name
  [selection]
  (or (:name selection)
      (:field-name selection)
      (gerror/throw-error (format "Selection Name is null for selection: %s." selection))))

(defn- expand-fragment [fragment-name fragments]
  (let [fragment (get fragments fragment-name)
        selection-set (:selection-set fragment)]
    (assert fragment (format "Cannot found fragment: %s." fragment-name))
    selection-set))

(defn- collect-selection
  [fragments col {:keys [node-type name] :as selection}]
  (case node-type
    :field           (conj col selection)
    :fragment-spread (into col (expand-fragment name fragments))
    (gerror/throw-error (format "selection type(%s) is not supported yet." node-type))))

(defn- collect-fields
  "CollectFields(objectType, selectionSet, visitedFragments)"
  [selection-set fragments]
  (reduce (partial collect-selection fragments) [] selection-set))

(defn- resolve-field-on-object
  [context schema resolver-fn parent-type parent-object field-entry field-type variables]
  (let [parent-type-name (:type-name parent-type)
        field-name (:field-name field-entry)
        field-arguments (type/get-field-arguments parent-type field-name)
        field-arguments-default (type/get-arguments-default-value-map field-arguments)
        field-argument-keys (->> field-arguments
                                 (map :argument-name)
                                 set)
        arguments (merge field-arguments-default
                         (build-arguments field-entry variables))
        resolver (resolver-fn parent-type-name field-name)
        required-argument-keys (->> field-arguments
                                    (filter :required)
                                    (map :argument-name)
                                    set)
        input-argument-keys (set (keys arguments))
        missing-arguments (set/difference required-argument-keys input-argument-keys)
        extra-arguments (set/difference input-argument-keys field-argument-keys)]
    (assert parent-type "Parent type is NULL!")
    (assert parent-type-name "Parent type name is NULL!")
    (assert field-name (format "Field name is empty for field: %s." field-entry))
    (when (pos? (count missing-arguments))
      (gerror/throw-error (format "Missing arguments: %s, for field (%s) in type (%s)." missing-arguments field-name parent-type-name)))
    (when (pos? (count extra-arguments))
      (gerror/throw-error (format "Arguments(%s) are not defined for field (%s) in type (%s)." extra-arguments field-name parent-type-name)))
    (if (not (empty? arguments))
      (resolver context parent-object arguments)
      (resolver context parent-object nil))))

(defn- is-enum-field-type?
  [field-type-meta]
  (assert field-type-meta "field-type-meta is NULL!")
  (= :ENUM (:kind field-type-meta)))

(defn- is-scalar-field-type?
  [field-type-meta]
  (assert field-type-meta "field-type-meta is NULL!")
  (= :SCALAR (:kind field-type-meta)))

(defn- is-object-field-type?
  [field-type-meta]
  (assert field-type-meta "field-type-meta is NULL!")
  (= :OBJECT (:kind field-type-meta)))

(defn- is-interface-field-type?
  [field-type-meta]
  (assert field-type-meta "field-type-meta is NULL!")
  (= :INTERFACE (:kind field-type-meta)))

(defn- is-union-field-type?
  [field-type-meta]
  (assert field-type-meta "field-type-meta is NULL!")
  (= :UNION (:kind field-type-meta)))

(defn- is-list-field-type?
  [field-type-meta]
  (assert field-type-meta "field-type-meta is NULL!")
  (= :LIST (:kind field-type-meta)))

(defn- is-not-null-type?
  [field-type-meta]
  (assert field-type-meta "field-type-meta is NULL!")
  (= :NOT_NULL (:kind field-type-meta)))

(declare execute-fields)

(defn- complete-value
  [context schema resolver-fn field-type result sub-selection-set fragments variables]
  ;;; TODO
  ;; (if (and (not nullable?)
  ;;          (nil? resolved-object))
  ;;   (throw ""))
  ;; FIXME
  (assert field-type "field-type is NULL!")
  (if (not (nil? result))
    (cond
      (is-scalar-field-type? field-type) result
      (is-enum-field-type? field-type) result
      (is-object-field-type? field-type) (execute-fields context schema resolver-fn field-type result sub-selection-set fragments variables)
      (is-interface-field-type? field-type) (execute-fields context schema resolver-fn field-type result sub-selection-set fragments variables)
      (is-list-field-type? field-type) (map #(complete-value context schema resolver-fn (type/get-inner-type schema field-type) % sub-selection-set fragments variables) result)
      (is-not-null-type? field-type) (let [not-null-result (complete-value context schema resolver-fn (type/get-inner-type schema field-type) result sub-selection-set fragments variables)]
                                       (if not-null-result  ;; TODO handle non-null as an overlay using required true
                                         not-null-result
                                         (gerror/throw-error (format "NOT_NULL type %s returns null." field-type))))
      :else (gerror/throw-error "Unhandled field type %s." field-type))
    (gerror/throw-error (format "result is NULL, while complete-value for field-type: %s" field-type))))

(defn- get-field-entry [context schema resolver-fn parent-type parent-object field-entry fragments variables]
  (assert field-entry (format "field-entry is NULL, for parent-type %s." parent-type))
  (assert parent-type (format "parent-type is NULL, for field-entry %s." field-entry))
  (let [response-key (get-selection-name field-entry)
        parent-type-name (:type-name parent-type)
        field-type (type/get-field-type schema parent-type-name (:field-name field-entry))]
    (assert response-key "response-key is NULL!")
    (assert field-type (format "field-type is NULL, for parent-type-name(%s) and response-key(%s)." parent-type-name response-key))
    (if (not (nil? field-type))
      (let [resolved-object (resolve-field-on-object context schema resolver-fn parent-type parent-object field-entry field-type variables)
            field-selection-set (:selection-set field-entry)
            fields  (collect-fields field-selection-set fragments)]
        (if (nil? resolved-object) ; when field is not-null field, resolved-object might be nil.
          [response-key nil] ; If resolvedObject is null, return
                             ; tuple(responseKey, null), indicating
                             ; that an entry exists in the result map
                             ; whose value is null.
          (let [response-value (complete-value context schema resolver-fn field-type resolved-object fields fragments variables)]
            [response-key response-value])))
      (gerror/throw-error (format "field-type is NULL for field(%s) in type(%s)!" response-key parent-type-name)))))

(defn- execute-fields
  [context schema resolver-fn parent-type root-value fields fragments variables]
  (assert parent-type "parent-type is NULL!")
  (->> fields
       (map #(get-field-entry context schema resolver-fn parent-type root-value % fragments variables))
       (into {})))

(defn- execute-query [context schema resolver-fn query fragments variables]
  (assert query "query is NULL!")
  (assert (:selection-set query) "query selection-set is NULL!")
  (let [selection-set (:selection-set query)
        object-type (type/get-root-query-type schema)
        fields (collect-fields selection-set fragments)]
    (execute-fields context schema resolver-fn object-type :root fields fragments variables)))

(defn- execute-mutation [context schema resolver-fn mutation fragments variables]
  (assert mutation "mutation is NULL!")
  (assert (:selection-set mutation) "mutation selection-set is NULL!")
  (let [selection-set (:selection-set mutation)
        object-type (type/get-root-mutation-type schema)
        fields (collect-fields selection-set fragments)]
    (execute-fields context schema resolver-fn object-type :root fields fragments variables)))

(defn- execute-definition
  [context schema resolver-fn definition fragments variables]
  (assert definition "definition is NULL!")
  (when variables (assert (map? variables) "Input variables is not a map."))
  (let [type                    (get-in definition [:operation-type :type])
        operation-variable-keys (set (map :variable-name (:variable-definitions definition)))
        input-variable-keys     (keys variables)
        missing-variables       (set/difference (set operation-variable-keys)
                                                (set input-variable-keys))]
    (if (pos? (count missing-variables))
      (gerror/throw-error (format "Missing variable(%s) in input variables." missing-variables)))
    (case type
      "query" (execute-query context schema resolver-fn definition fragments variables)
      "mutation" (execute-mutation context schema resolver-fn definition fragments variables)
      (gerror/throw-error (format "Unhandled operation root type: %s." definition)))))

(defn- group-by-first [k vs]
  (some->> vs (group-by k) (map (fn [[k v]] [k (first v)])) (into {})))

(defn- execute-document
  [context schema resolver-fn document variables]
  (let [operation-definitions (:operation-definitions document)
        fragments             (some->> document :fragment-definitions (group-by-first :name))]
    (cond
      (empty? operation-definitions) (gerror/throw-error (format "Document is invalid (%s)." document))
      :else {:data (into {} (map (fn [definition]
                                   (execute-definition context schema resolver-fn definition fragments variables))
                                 operation-definitions))})))

(defn- prepare [schema-or-state resolver-fn statement-or-state]
  (let [state (if (or (:errors schema-or-state) (:spec-map schema-or-state))
                schema-or-state
                (validator/validate-schema schema-or-state))] ;; Schema validation inside execution phase for backwards compatibility
    (if (:errors state)
      (select-keys state [:errors])
      (let [validated-statement (if (string? statement-or-state)
                                  (-> statement-or-state parser/parse (validator/validate-statement state))
                                  statement-or-state)]
        (if (-> validated-statement :state :errors)
          (select-keys (:state validated-statement) [:errors])
          (let [resolver (resolver/create-resolver-fn (:schema (:state validated-statement)) resolver-fn)]
            (assoc-in validated-statement [:state :resolver] resolver)))))))

;; Public API

(defn execute
  ([context schema-or-state resolver-fn statement-or-state]
   (execute context schema-or-state resolver-fn statement-or-state nil))
  ([context schema-or-state resolver-fn statement-or-state variables]
   (let [{:keys [errors state document] :as result} (prepare schema-or-state resolver-fn statement-or-state)]
     (if errors
       (select-keys result [:errors])
       (try
         (execute-document context (:schema state) (:resolver state) document variables)
         (catch Exception e
           (if-let [error (ex-data e)]
             {:errors [error]}
             (throw e))))))))
