(ns more.async.dataflow.specs
  (:require
   [clojure.spec.alpha :as s]))

(defn kw->fn
  [kw]
  (when kw (resolve (symbol kw))))

(defn kfn? [kw] (ifn? (kw->fn kw)))

(s/def ::name (s/or :keyword keyword?
                    :string string?
                    :number number?
                    :symbol symbol?))
