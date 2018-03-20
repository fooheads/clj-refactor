(ns clj-refactor.edit
  (:require
    [cljfmt.core :as fmt]
    [clojure.string :as string]
    [clojure.zip :as cz]
    [rewrite-clj.node :as n]
    #?(:clj [rewrite-clj.node.forms]
       :cljs [rewrite-clj.node.forms :refer FormsNode])

    [rewrite-clj.node.protocols :as np]
    [rewrite-clj.paredit :as p]
    [rewrite-clj.zip :as z]
    [rewrite-clj.zip.utils :as zu]
    [rewrite-clj.zip.whitespace :as ws])
  #?(:clj (:import (rewrite_clj.node.forms FormsNode))))

(defn top? [loc]
  (= FormsNode (type (z/node loc))))

#?(:clj (defn zdbg [loc msg]
        (prn (z/string loc) msg)
        loc)

   :cljs (defn zdbg [loc msg]
           (if (exists? js/debug)
             (js/debug (pr-str (z/string loc)) msg)
             (doto (z/string loc) (prn msg)))
           loc))

(defn exec-while [loc f p?]
  (->> loc
       (iterate f)
       (take-while p?)
       last))

(defn to-top [loc]
  (if (top? loc)
    loc
    (exec-while loc z/up (complement top?))))

(defn to-first-top [loc]
  (-> loc
      (to-top)
      (z/leftmost)))

(defn parent-let? [zloc]
  (= 'let (-> zloc z/up z/leftmost z/sexpr)))

(defn find-op
  [zloc]
  (if (z/seq? zloc)
    (z/down zloc)
    (z/leftmost zloc)))

(defn find-ops-up
  [zloc & op-syms]
  (let [oploc (find-op zloc)]
    (if (contains? (set op-syms) (z/sexpr oploc))
      oploc
      (let [next-op (z/leftmost (z/up oploc))]
        (when-not (= next-op zloc)
          (apply find-ops-up next-op op-syms))))))

(defn single-child?
  [zloc]
  (let [child (z/down zloc)]
    (and (z/leftmost? child)
         (z/rightmost? child))))

;; TODO Is this safe?
(defn join-let
  "if a let is directly above a form, will join binding forms and remove the inner let"
  [let-loc]
  (let [bind-node (z/node (z/next let-loc))]
    (if (parent-let? let-loc)
      (do
        (-> let-loc
            (z/right) ; move to inner binding
            (z/right) ; move to inner body
            (p/splice-killing-backward) ; splice into parent let
            (z/leftmost) ; move to let
            (z/right) ; move to parent binding
            (z/append-child bind-node) ; place into binding
            (z/down) ; move into binding
            (z/rightmost) ; move to nested binding
            (z/splice) ; remove nesting
            (z/left)
            (ws/append-newline)
            (z/up) ; move to new binding
            (z/leftmost))) ; move to let
      let-loc)))

(defn remove-right [zloc]
  (-> zloc
      (zu/remove-right-while ws/whitespace?)
      (zu/remove-right-while (complement ws/whitespace?))))

(defn remove-left [zloc]
  (-> zloc
      (zu/remove-left-while ws/whitespace?)
      (zu/remove-left-while (complement ws/whitespace?))))

(defn transpose-with-right
  [zloc]
  (if (z/rightmost? zloc)
    zloc
    (let [right-node (z/node (z/right zloc))]
      (-> zloc
          (remove-right)
          (z/insert-left right-node)))))

(defn transpose-with-left
  [zloc]
  (if (z/leftmost? zloc)
    zloc
    (let [left-node (z/node (z/left zloc))]
      (-> zloc
          (z/left)
          (transpose-with-right)))))

(defn find-namespace [zloc]
  (-> zloc
      (to-first-top) ; go to top form
      (z/find-next-value z/next 'ns) ; go to ns
      (z/up))) ; ns form

;; TODO this can probably escape the ns form - need to root the search it somehow (z/.... (z/node zloc))
(defn find-or-create-libspec [zloc v]
  (if-let [zfound (z/find-next-value zloc z/next v)]
    zfound
    (-> zloc
        (z/append-child (n/newline-node "\n"))
        (z/append-child (list v))
        z/down
        z/rightmost
        z/down
        z/down)))

(defn remove-children
  [zloc]
  (if (z/seq? zloc)
    (z/replace zloc (n/replace-children (z/node zloc) []))
    zloc))

(defn remove-all-after
  [zloc]
  (loop [loc (zu/remove-right-while (remove-children zloc) (constantly true))]
    (if-let [uploc (z/up loc)]
      (recur (zu/remove-right-while uploc (constantly true)))
      loc)))

(defn read-position
  [old-pos zloc offset]
  (let [n (-> zloc
              (remove-all-after)
              (z/root-string)
              (z/of-string)
              (z/rightmost)
              (z/find-next-depth-first (comp z/end? z/next)))]
    (if n
      (-> n
          (z/node)
          (meta)
          ((juxt :row (comp (partial + offset) :col))))
      old-pos)))

(defn mark-position
  [zloc marker]
  (z/replace zloc (update (z/node zloc) ::markers (fnil conj #{}) marker)))

(defn find-mark-or-nil
  [zloc marker]
  (z/find (to-first-top zloc) z/next (fn [loc] (contains? (get (z/node loc) ::markers) marker))))

(defn find-mark
  [zloc marker]
  (if-let [mloc (find-mark-or-nil zloc marker)]
    mloc
    zloc))

(defn remove-mark
  [zloc marker]
  (z/replace zloc (update (z/node zloc) ::markers disj marker)))

(defn find-first-sexpr
  [zloc search-sexpr]
  (-> zloc
      (to-first-top)
      (z/find z/next #(= (z/sexpr %) search-sexpr))))

(defn replace-all-sexpr
  [zloc sexpr def-name mark?]
  (if-let [found-loc (find-first-sexpr zloc sexpr)]
    (let [new-loc (if mark?
                    (mark-position (z/replace found-loc def-name) :new-cursor)
                    (z/replace found-loc def-name))]
      (recur (mark-position new-loc :reformat) sexpr def-name false))
    zloc))

(defn format-form
  [zloc]
  (let [expr-loc (to-top zloc)
        formatted-node (fmt/reformat-form (z/node expr-loc) {})]
    (z/replace expr-loc formatted-node)))

(defn format-all
  [zloc]
  (loop [top-loc (to-first-top zloc)]
    (let [formatted (format-form top-loc)]
      (if (z/rightmost? formatted)
        formatted
        (recur (z/right formatted))))))

(defn format-marked
  [zloc]
  (let [floc (find-mark-or-nil (to-first-top zloc) :reformat)]
    (cond
      floc (recur (z/replace floc (fmt/reformat-form (z/node (remove-mark floc :reformat)) {})))
      :else zloc)))
