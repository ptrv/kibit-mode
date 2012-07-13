(ns kibit-mode.source-attribution
  (:require [clojure.core.logic :as l])
  (:import (java.lang String Long Double Boolean Character)
           (java.math BigDecimal)
           (clojure.lang Keyword
                         Symbol
                         Ratio
                         IPersistentVector
                         IPersistentList
                         IPersistentSet
                         IPersistentMap)))

;;; NOTES:

;; Seems the best way to go is to map the input expr and source file
;; into the same coordinate space, validate what we can, and infer
;; what we can't.

;;e.g. The list (:a :b [1 2]) would generate the following coordinate
;;space

;; { [0] (:a :b [1 2])
;;   [0 0] :a
;;   [0 1] :b
;;   [0 2] [1 2]
;;   [0 2 0] 1
;;   [0 2 1] 2}

;; meanwhile the string " (:a :b\n\n[1 2]) would map into the same
;; coordinate space

;; { [0] "(:a :b\n\n[1 2])"
;;   [0 0] ":a"
;;   [0 1] ":b"
;;   [0 2] "[1 2]"
;;   [0 2 0] "1"
;;   [0 2 1] "2"}

;; Given the two maps, the map of objects is authoritative and the map
;; of strings is hypothetical. Reader literals right now can only be a
;; tag and one value, which means there's no ambiguity about how a
;; list of string tokens should collapse to values, only which values
;; they collapse to

;; After generating the two maps, we can then validate the matches.

;; PROBLEM: String representation of Sets has an authoritative
;; ordering, data doesn't

;; PROBLEM: A set containing two reader literals that are unknown has
;; no ordering cues to hint at which value in the set came from which
;; reader literal. We could adaptively learn reader literal -> type
;; mappings, perhaps.

;; String map needs metadata. Maybe the values of both maps are
;; themselves maps and can indicate if they are authoritative or not.

(l/defrel matched-by type matcher-fn)
(l/facts matched-by
         [[Object (fn [object] (re-pattern (str "\\Q" (pr-str object) "\\E")))]])

(l/defrel collection-bounds type start-string end-string)
(l/facts collection-bounds
         [[IPersistentVector "[" "]"]
          [IPersistentList "(" ")"]
          [IPersistentSet "#{" "}"]
          [IPersistentMap "{" "}"]])


;; Scalars are values that the reader may return, and represent a
;; single scalar value, in direct opposition to collections.

(defn scalar-value?
  [instance]
  (l/project [instance]
             (l/== false (coll? instance))))

;; Vectors are values that the reader may return, and represent zero
;; or more scalar values grouped together, potentially with some
;; ordering, e.g. collections.

(defn vector-value?
  [instance]
  (l/project [instance]
             (l/== true (coll? instance))))

;; Primitives are values that cannot have metadata associated with
;; them. All primitives should be scalars. Not all scalars are
;; primitive.

(defn primitive-value?
  [instance]
  (l/project [instance]
             (l/== false (isa? (class instance) clojure.lang.IObj))))

;; Due to projections this goal will likely only work with instance
;; grounded. Still valuable as there may be many different ways to
;; express the same value, e.g. the Long 48 can be expressed as the
;; string "48" or as the string "14r36"

(defn matching-regexpo
  [instance regexp]
  (l/fresh [re-gen]
           (l/conde ((scalar-value? instance)
                     (matched-by Object re-gen)
                     (l/project [instance re-gen]
                                (l/== regexp (re-gen instance)))))))

;; Goal to relate a expression (an object read from Clojure's reader)
;; and a source block with precise indices in the source block the
;; describe where and only where that expression was read from.

(defn scalar-sourceo
  [expr source source-match start-index end-index]
  (l/fresh [regexp]
           (scalar-value? expr)
           (matching-regexpo expr regexp)
           (l/project [source regexp]
                      (l/== source-match (re-find regexp source)))
           (l/project [source regexp]
                      (l/== [start-index end-index]
                            (let [matcher (re-matcher regexp source)
                                  _ (.find matcher)
                                  start (.start matcher)
                                  end (.end matcher)]
                              [start end])))))

(defn vector-sourceo
  [expr source source-match start-index end-index]
  (l/fresh [collection-type collection-start collection-end]
           (vector-value? expr)
           (collection-bounds collection-type collection-start collection-end)
           (l/project [collection-type expr]
                      (l/== true (isa? (class expr) collection-type)))
           (l/project [source collection-start]
                      (l/== start-index (.indexOf source collection-start)))
           (l/project [source collection-end]
                      (l/== end-index (inc (.indexOf source collection-end))))
           (l/project [source start-index end-index]
                      (l/== source-match (.substring source start-index end-index)))))

(defn list-sourceo
  "Special goal to check lists, because core.logic may coerce them into lazy seqs."
  [expr source source-match start-index end-index]
  (let [isa-list (isa? (class expr) IPersistentList)]
    (l/fresh [collection-start collection-end]
             (l/== true isa-list)
             (collection-bounds IPersistentList collection-start collection-end)
             (l/project [source collection-start]
                        (l/== start-index (.indexOf source collection-start)))
             (l/project [source collection-end]
                        (l/== end-index (inc (.indexOf source collection-end))))
             (l/project [source start-index end-index]
                        (l/== source-match (.substring source start-index end-index))))))

(defn sourceo
  [expr source source-match start-index end-index]
  (l/conde ((scalar-sourceo expr source source-match start-index end-index))
           ((vector-sourceo expr source source-match start-index end-index))
           ((list-sourceo expr source source-match start-index end-index))))

(defprotocol SpottyMeta
  (spotty-meta [value meta] "Returns `value` with `meta` associated if
  it implements IObj, `value` unchanged otherwise"))

(extend-protocol SpottyMeta
  clojure.lang.IObj
  (spotty-meta
    [this meta]
    (with-meta this meta))
  java.lang.Object
  (spotty-meta
    [this meta]
    this))

(defn match-info
  "Return a vector of [`source-match` `starting-match-index`
  `ending-match-index`] given an `expr` and a string `source` that
  contained that expression."
  [source expr]
  (first (l/run 1 [q] (l/fresh [source-match start-index end-index]
                         (l/== q [source-match start-index end-index])
                         (sourceo expr source source-match start-index end-index)))))

(defn match-meta
  "Return a map of sub-expression source attribution metadata, given
  the parent's source attribution metadata `parent-meta`, and a
  [`source-match` `starting-match-index` `ending-match-index`] vector
  `match-info` as returned from match-info. Returns an empty map if
  there is not sufficient match info."
  [{:keys [source line start-character] :as parent-meta} match-info]
  (if (empty? match-info)
    {}
    (let [[source-match starting-match-index ending-match-index] match-info
          start-line (+ line (count (re-seq #"\n" (.substring source 0 starting-match-index))))
          end-line (+ start-line (count (re-seq #"\n" source-match)))]
      {:start-character (+ start-character starting-match-index)
       :end-character (+ start-character ending-match-index)
       :line start-line
       :end-line end-line
       :source source-match})))

(defn source-of
  "Return `expr` with appropriately scoped source attribution metadata
  from `form` attached."
  [form expr]
  (if (= form expr)
    expr
    (let [{source :source :as parent-meta} (meta form)
           match-info (match-info source expr)
           meta (match-meta parent-meta match-info)]
      (spotty-meta expr meta))))

(defn detailed-exprs-with-meta
  "Given `form` with source attribution metadata, yield a recursive
  descent, fifo seq of all the exprs in `form` with appropriately
  scoped source attribution metadata attached"
  [form]
  (let [exprs (tree-seq sequential? seq form)]
    (map (partial source-of form) exprs)))
