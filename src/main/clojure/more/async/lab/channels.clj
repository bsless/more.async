(ns more.async.lab.channels
  (:import
   [clojure.core.async.impl.channels ManyToManyChannel]))

(let [m {:tag 'clojure.core.async.impl.channels.ManyToManyChannel}]
  (defn reopen!
    {:inline
     (fn [ch] `(reset! (.closed ~(with-meta ch m)) false))}
    [ch]
    (reset! (.closed ^ManyToManyChannel ch) false)))
