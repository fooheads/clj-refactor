(ns clj-refactor.test-helper
  (:require
    #?(:clj [clojure.tools.reader :as r] 
       :cljs [cljs.tools.reader :as r])

    [rewrite-clj.zip :as z]))

(defn str-zip-to
  [form-str goto f]
  (-> form-str
      (z/of-string)
      (z/find z/next #(= (z/sexpr %) goto))
      (f)))

(defn zip-to
  [form goto f]
  (-> form
      (str)
      (str-zip-to goto f)))

(defn apply-zip-to
  [form goto f]
  (z/sexpr (zip-to form goto f)))

(defn apply-zip
  [form goto f]
  (-> (zip-to form goto f)
      (z/root-string)
      (r/read-string)))

(defn apply-zip-str
  [form-str goto f]
  (-> form-str
      (str-zip-to goto f)
      (z/root-string)))

(defn apply-zip-root
  [form goto f]
  (r/read-string
    (str "(do "
         (z/root-string (zip-to form goto f))
         ")")))

(defn apply-zip-str-root
  [form-str goto f]
  (r/read-string
    (str "(do "
         (z/root-string (str-zip-to form-str goto f))
         ")")))
