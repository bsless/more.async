(ns hooks.consume
  (:require [clj-kondo.hooks-api :as api]))

(defn consume [{:keys [:node]}]
  (let [[ch sym & body] (rest (:children node))]
    (when-not (and sym)
      (throw (ex-info "No sym and val provided" {})))
    (let [new-node (api/list-node
                    (list
                     (api/token-node 'clojure.more.async/consume-call!)
                     ch
                     (api/list-node
                      (list*
                       (api/token-node 'fn*)
                       (api/vector-node [sym])
                       body))))]
      {:node new-node})))

(defn consume-blocking [{:keys [:node]}]
  (let [[ch sym & body] (rest (:children node))]
    (when-not (and sym)
      (throw (ex-info "No sym and val provided" {})))
    (let [new-node (api/list-node
                    (list
                     (api/token-node 'clojure.more.async/consume-call!!)
                     ch
                     (api/list-node
                      (list*
                       (api/token-node 'fn*)
                       (api/vector-node [sym])
                       body))))]
      {:node new-node})))

(defn consume? [{:keys [:node]}]
  (let [[ch sym & body] (rest (:children node))]
    (when-not (and sym)
      (throw (ex-info "No sym and val provided" {})))
    (let [new-node (api/list-node
                    (list
                     (api/token-node 'clojure.more.async/consume-checked-call!)
                     ch
                     (api/list-node
                      (list*
                       (api/token-node 'fn*)
                       (api/vector-node [sym])
                       body))))]
      {:node new-node})))

(defn consume-blocking? [{:keys [:node]}]
  (let [[ch sym & body] (rest (:children node))]
    (when-not (and sym)
      (throw (ex-info "No sym and val provided" {})))
    (let [new-node (api/list-node
                    (list
                     (api/token-node 'clojure.more.async/consume-checked-call!!)
                     ch
                     (api/list-node
                      (list*
                       (api/token-node 'fn*)
                       (api/vector-node [sym])
                       body))))]
      {:node new-node})))
