(ns specql.impl.util
  (:require [clojure.spec.alpha :as s]
            [specql.impl.registry :as registry]
            [clojure.string :as str]
            [specql.transform :as xf]
            [specql.impl.composite :as composite]))

(defn assert-spec
  "Unconditionally assert that value is valid for spec. Returns value."
  [spec value]
  (assert (s/valid? spec value)
          (s/explain-str spec value))
  value)

(defn assert-table [table]
  (assert (@registry/table-info-registry table)
          (str "Unknown table " table ", call define-tables!")))

(defn q
  "Surround string with doublequotes"
  [string]
  (str "\"" string "\""))

(defn sql-columns-list [cols]
  (str/join ", "
            (map (fn [[col-alias [col-name _]]]
                   (str col-name " AS " (name col-alias)))
                 cols)))

(defn gen-alias
  "Returns a function that create successively numbered aliases for keywords."
  []
  (let [alias (volatile! 0)]
    (fn [named]
      (let [n (name named)]
        (str (subs n 0 (min (count n) 3))
             (vswap! alias inc))))))

(defn fetch-columns
  "Return a map of column alias to vector of [sql-accessor result-path]"
  [table-info-registry table table-alias alias-fn column->path]
  (let [table-columns (:columns (table-info-registry table))]
    (reduce
     (fn [cols [column-kw result-path]]
       (let [col (table-columns column-kw)
             name (:name col)
             composite-type-kw (registry/composite-type (:type col))]
         (assert name (str "Unknown column " column-kw " for table " table))
         (if composite-type-kw
           ;; This field is a composite type, add "(field).subfield" accessors
           ;; for each field in the type
           (merge cols
                  (into {}
                        (map (fn [[field-kw field]]
                               [(keyword (alias-fn name))
                                [(str "(" table-alias ".\"" name "\").\"" (:name field) "\"")
                                 (into result-path [field-kw])
                                 col]]))
                        (:columns (table-info-registry composite-type-kw))))
           ;; A regular field
           (assoc cols
                  (keyword (alias-fn name))
                  [(str table-alias ".\"" name "\"") result-path col]))))
     {} column->path)))

(defn map-vals [f m]
  (into {}
        (map (juxt first (comp f second)))
        m))

(defn transform-value-to-sql [{transform ::xf/transform :as column} value]
  (if transform
    (xf/to-sql transform value)
    value))

(defn transform-to-sql
  "Transform column values to SQL "
  [table-info-registry {:keys [columns] :as table} record]
  (if-not columns
    record
    (let [column-xf #(some-> % columns ::xf/transform)]
      (reduce
       (fn [record [key val]]
         (if-let [xf (column-xf key)]
           (assoc record key (xf/to-sql xf val))
           (if (map? val)
             (assoc record key
                    (transform-to-sql table-info-registry
                                      (table-info-registry (:type (columns key)))
                                      val))
             record)))
       record record))))

(defn columns-and-values-to-set
  "Return columns and values for set (update or insert)"
  [table-info-registry table record]
  (let [table-columns (:columns (table-info-registry table))]
    (loop [names []
           value-names []
           value-parameters []
           [[column-kw value] & columns] (seq record)]
      (if-not column-kw
        [names value-names value-parameters]
        (let [column (table-columns column-kw)]
          (assert column (str "Unknown column " (pr-str column-kw) " for table " (pr-str table)))
          (if (= "A" (:category column))
            ;; This is an array, serialize it
            (recur (conj names (:name column))
                   (conj value-names (str "?::" (subs (:type column) 1) "[]"))
                   (conj value-parameters (composite/stringify table-info-registry column value true))
                   columns)

            (if-let [composite-type-kw (registry/composite-type table-info-registry (:type column))]
              ;; This is a composite type, add ROW(?,...)::type value
              (let [composite-type (table-info-registry composite-type-kw)
                    composite-columns (:columns composite-type)]
                (recur (conj names (:name column))
                       (conj value-names
                             (str "ROW("
                                  (str/join "," (repeat (count composite-columns) "?"))
                                  ")::"
                                  (:name composite-type)))
                       ;; Get all values in order and add to parameter value
                       (into value-parameters
                             (map (comp (partial get value) first)
                                  (sort-by (comp :number second) composite-columns)))
                       columns))

              (if (:enum? column)
                ;; Enum type, add value with ::enumtype cast
                (recur (conj names (:name column))
                       (conj value-names (str "?::" (:type column)))
                       (conj value-parameters value)
                       columns)

                ;; Normal value, add name and value
                (recur (conj names (:name column))
                       (conj value-names "?")
                       (conj value-parameters value)
                       columns)))))))))
