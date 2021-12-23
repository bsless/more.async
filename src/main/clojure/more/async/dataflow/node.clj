(ns more.async.dataflow.node
  (:require
   [clojure.spec.alpha :as s]
   [clojure.core.async :as a]
   [more.async :as ma]
   [clojure.data]))

(s/def ::name (s/or :keyword keyword?
                    :string string?
                    :number number?
                    :symbol symbol?))

(s/def ::direction #{::in ::out})

(defmulti -type ::type)

(defmulti -compile (fn [node _env] (get node ::type)))

(defmulti ports ::type)

(s/def ::to ::name)
(s/def ::to* (s/* ::to))
(s/def ::to-map (s/map-of any? ::to))
(s/def ::from ::name)
(s/def ::size nat-int?)
(s/def ::xf fn?)
(s/def ::rf fn?)
(s/def ::fn fn?)
(s/def ::init-fn fn?)
(s/def ::async? boolean?)
(s/def ::timeout pos-int?)

;;; PIPELINE

(s/def ::pipeline (s/keys :req [::to ::from ::size ::xf]))
(defmethod -type ::pipeline-blocking [_] (s/keys :req [::name ::pipeline]))
(defmethod -type ::pipeline-async    [_] (s/keys :req [::name ::pipeline]))
(defmethod -type ::pipeline          [_] (s/keys :req [::name ::pipeline]))

(defmethod -compile ::pipeline
  [{{to ::to from ::from size ::size xf ::xf} ::pipeline} env]
  (a/pipeline size (env to) xf (env from)))

(defmethod -compile ::pipeline-blocking
  [{{to ::to from ::from size ::size xf ::xf} ::pipeline} env]
  (a/pipeline-blocking size (env to) xf (env from)))

(defmethod -compile ::pipeline-async
  [{{to ::to from ::from size ::size af ::xf} ::pipeline} env]
  (a/pipeline-async size (env to) af (env from)))

(doseq [t [::pipeline ::pipeline-blocking ::pipeline-async]]
  (defmethod ports t
    [{{to ::to from ::from} ::pipeline}]
    #{{::name from ::direction ::in}
      {::name to ::direction ::out}}))

(comment
  (s/explain-data
   ::pipeline
   {::type ::pipeline
    ::from "from"
    ::to "to"
    ::size 4
    ::xf identity}))

;;; BATCH

(s/def ::finally-fn fn?)

(s/def ::batch
  (s/keys :req [::from ::to ::size ::timeout]
          :opt [::rf ::init-fn ::async? ::finally-fn]))


(defmethod -type ::batch [_]
  (s/keys :req [::name ::batch]))

(defmethod -compile ::batch
  [{{from ::from
     to ::to
     size ::size
     timeout ::timeout
     rf ::rf
     init ::init-fn
     finally ::finally-fn
     async? ::async?
     :or {rf conj init (constantly []) finally identity}}
    ::batch} env]
  (let [from (env from)
        to (env to)]
    (if async?
      (ma/batch! from to size timeout rf init finally)
      (a/thread (ma/batch!! from to size timeout rf init finally)))))

(defmethod ports ::batch
  [{{to ::to from ::from} ::batch}]
  #{{::name from ::direction ::in}
    {::name to ::direction ::out}})

(comment
  (s/explain-data
   ::batch
   {::type ::batch
    ::from "from"
    ::to "to"
    ::size 4
    ::timeout 4}))

;;; MULT


(s/def ::mult (s/keys :req [::from] :opt [::to*]))
(defmethod -type ::mult [_] (s/keys :req [::name ::mult]))

(defmethod -compile ::mult
  [{{from ::from to :to*} ::mult} env]
  (let [mult (a/mult (env from))]
    (doseq [ch to] (a/tap mult (env ch)))
    mult))

(defmethod ports ::mult
  [{{to ::to from ::from} ::mult}]
  (into
   #{{::name from ::direction ::in}}
   (map (fn [to] {::name to ::direction ::out}))
   to))

(comment
  (s/explain-data
   ::mult
   {::from :in
    ::to* [:cbp-in :user-in]})
  (ports
   {::type ::mult
    ::name :cbm
    ::mult {::from :in ::to [:cbp-in :user-in]}}))

;;; TODO ::mix

;;; PUBSUB

(s/def ::pubsub (s/keys :req [::pub ::topic-fn] :opt [::sub]))
(s/def ::pub ::name)
(s/def ::sub (s/* (s/keys :req [::topic ::name])))
(s/def ::topic any?)
(s/def ::topic-fn ifn?)

(comment
  (s/explain-data
   ::pubsub
   {::pub :in
    ::topic-fn (constantly 0)
    ::sub [{::topic 0 ::name ::out}]}))

(defmethod -type ::pubsub [_] (s/keys :req [::name ::pubsub]))

(defmethod -compile ::pubsub
  [{{pub ::pub sub ::sub tf ::topic-fn} ::pubsub} env]
  (let [p (a/pub (env pub) tf)]
    (doseq [{:keys [:sub/topic :sub/chan]} sub]
      (a/sub p topic (env chan)))
    p))

(defmethod ports ::pubsub
  [{{to ::sub from ::pub} ::pubsub}]
  (into
   #{{::name from
      ::direction ::in}}
   (map (fn [to] {::name to ::direction ::out}))
   to))

;;; PRODUCER

(s/def ::produce (s/keys :req [::to ::fn] :opt [::async?]))

(defmethod -type ::produce [_] (s/keys :req [::name ::produce]))

(defmethod -compile ::produce
  [{{ch ::to f ::fn async? ::async?} ::produce} env]
  (let [ch (env ch)]
    (if async?
      (ma/produce-call! ch f)
      (a/thread (ma/produce-call!! ch f)))))

(defmethod ports ::produce
  [{{to ::to} ::produce}]
  #{{::name to ::direction ::out}})

;;; CONSUMER

(s/def ::checked? boolean?)
(s/def ::consume (s/keys :req [::from ::fn] :opt [::async? ::checked?]))

(defmethod -type ::consume [_] (s/keys :req [::name ::consume]))

(defmethod -compile ::consume
  [{{ch ::from f ::fn async? ::async? checked? ::checked?} ::consume} env]
  (let [ch (env ch)]
    (if async?
      ((if checked?
         ma/consume-checked-call!
         ma/consume-call!) ch f)
      (a/thread ((if checked?
                   ma/consume-checked-call!!
                   ma/consume-call!!) ch f)))))

(defmethod ports ::consume
  [{{from ::from} ::consume}]
  #{{::name from ::direction ::in}})

;;; SPLIT

(s/def ::split (s/keys :req [::from ::to-map ::fn] :opt [::dropping?]))
(s/def ::dropping? boolean?)

(defmethod -type ::split [_] (s/keys :req [::name ::split]))

(defmethod -compile ::split
  [{{from ::from to ::to-map f ::fn
     dropping? ::dropping?} ::split} env]
  ((if dropping? ma/split?! ma/split!) f (env from) (env to)))

(defmethod ports ::split
  [{{to ::to-map from ::from} ::split}]
  (into
   #{{::name from ::direction ::in}}
   (map (fn [to] {::name to ::direction ::out}))
   (vals to)))

;;; REDUCTIONS

(s/def ::reductions
  (s/keys :req [::from ::to ::rf ::init-fn]
          :opt [::async?]))

(defmethod -type ::reductions [_]
  (s/keys :req [::name ::type ::reductions]))

(defmethod -compile ::reductions
  [{{from ::from
     to ::to
     rf ::rf
     init ::rf
     async? ::async?} ::reductions} env]
  (let [from (env from)
        to (env to)]
    (if async?
      (ma/reductions! rf init from to)
      (a/thread
        (ma/reductions!! rf init from to)))))

(defmethod ports ::reductions
  [{{to ::to from ::from} ::reductions}]
  #{{::name from ::direction ::in}
    {::name to ::direction ::out}})
