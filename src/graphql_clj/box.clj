(ns graphql-clj.box
  (:import [clojure.lang IObj IDeref IPersistentCollection Named]))

(deftype Box [value meta]

  IObj
  (meta [_] meta)

  IDeref
  (deref [_] (if (instance? Box value) @value value))

  IPersistentCollection
  (equiv [a b] (.equals a b))

  Named
  (getName [_] (if (instance? Box value) (.getName value) (str value)))

  Object
  (equals [_ b] (if (instance? Box b) (= value @b) (= value b)))
  (hashCode [_] (.hashCode value))
  (toString [_] (str value)))

(defn kv->box
  "Given a single key value pair in a map, preserve metadata for the value."
  [value]
  (let [m (meta value)
        [[k v]] (vec value)]
    {k (->Box v m)}))

(defn box->val
  "Given a potentially boxed value, strip the box and return the raw value.
   Same as identity if value is already unboxed"
  [v]
  (if (instance? Box v) @v v))
