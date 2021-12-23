(ns more.async.dataflow
  (:require
   [more.async.dataflow.node :as node]
   [more.async.dataflow.channel :as chan]
   [clojure.spec.alpha :as s]
   [clojure.core.async :as a]))

(defn connected?
  [node chans]
  (every?
   #(contains? chans (::node/name %))
   (node/ports node)))

(defn connected-model?
  [{::keys [channels nodes]}]
  (let [chans (into #{} (map ::chan/name) channels)]
    (every? #(connected? % chans) nodes)))

(s/def ::connected connected-model?)

(s/def ::node (s/multi-spec node/-type ::node/type))
(s/def ::channel (s/multi-spec chan/-type ::chan/type))

(s/def ::channels (s/+ ::channel))
(s/def ::nodes (s/+ ::node))

(s/def ::model (s/keys :req [::channels ::nodes]))

(s/def ::correct-model (s/and ::connected))

(defn- build
  [k f specs]
  (reduce
   (fn [m spec]
     (assoc m (get spec k) (f spec)))
   {}
   specs))

(defn -env
  [chans]
  (fn [lookup]
    (if-some [ch (get chans lookup)]
      ch
      (throw (ex-info (format "Channel %s not found" lookup) chans)))))

(defn compile-model
  [{::keys [channels nodes env]
    :or {env -env}}]
  (let [chans (build ::chan/name chan/-compile channels)
        env (env chans)
        workers (build ::node/name #(node/-compile % env) nodes)]
    {::channels chans ::nodes workers}))

(s/fdef compile-model
  :args (s/cat :model ::model))

(comment
  (def model
    {::channels
     [{::chan/name :in
       ::chan/type ::chan/sized
       ::chan/size 1}
      {::chan/name :out
       ::chan/type ::chan/sized
       ::chan/size 1}]
     ::nodes
     [

      {::node/name :producer
       ::node/type ::node/produce
       ::node/produce
       {::node/to :in
        ::node/async true
        ::node/fn (let [a (atom 0)]
                    (fn drive []
                      (Thread/sleep 1000)
                      (swap! a inc)))}}

      {::node/name :pipeline
       ::node/type ::node/pipeline-blocking
       ::node/pipeline
       {::node/from :in
        ::node/to :out
        ::node/size 4
        ::node/xf (map (fn [x] (println x) (Thread/sleep 2500) x))}}

      {::node/name :consumer
       ::node/type ::node/consume
       ::node/consume
       {::node/from :out
        ::node/fn (fn [x] (println :OUT x))
        ::node/async? true}}]})

  (s/valid? ::channels (::channels model))

  (s/valid? ::nodes (::nodes model))

  (s/valid? ::model model)

  (s/valid? ::connected model)

  (def system (compile-model model))

  (a/close! (:in (::channels system))))
