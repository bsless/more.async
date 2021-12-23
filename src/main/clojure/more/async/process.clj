(ns more.async.process
  (:import
   (java.util.concurrent.atomic AtomicBoolean))
  (:require
   [clojure.core.async :as a]))

(defonce *registry* (atom {}))

(defn server-process
  [in f init ^AtomicBoolean mail? control]
  (a/go-loop [f f
              state init]
    (if (.get mail?)
      (let [[f state]
            (loop [f f
                   state state]
              (let [c (a/poll! control)]
                (if (nil? c)
                  [f state]
                  (let [[f state] (c f state)]
                    (recur f state)))))]
        (.set mail? false)
        (if f
          (recur f state)
          nil))
      (let [v (a/<! in)]
        (if (nil? v)
          nil
          (recur f (f state v)))))))

(defn migrate
  ([state-fn]
   (fn [f state]
     [f (state-fn state)]))
  ([fn-fn state-fn]
   (fn [f state]
     [(fn-fn f) (state-fn state)])))

(defn restart
  ([init]
   (fn [f _state]
     [f init]))
  ([f init]
   (fn [_ _]
     [f init])))

(defn effect
  [f]
  (fn [g state]
    (f state)
    [g state]))

(defn stop [] (fn [_ _] [nil nil]))

(defprotocol IProcess
  (-start [this])
  (-stop [this])
  (-restart [this init] [this f init])
  (-migrate [this state-fn] [this fn-fn state-fn])
  (-effect [this f]))

(defrecord ServerProcess
    [in f init ^AtomicBoolean mail? control event-loop]
  IProcess
  (-start [this]
    (assoc this :event-loop (server-process in f init mail? control)))
  (-stop [_]
    (.set mail? true)
    (a/go (a/>! control (stop)))
    (a/close! in))

  (-restart [_ init]
    (.set mail? true)
    (a/go (a/>! control (restart init))))
  (-restart [_ init state]
    (.set mail? true)
    (a/go (a/>! control (restart init state))))
  (-migrate [_ state-fn]
    (.set mail? true)
    (a/go (a/>! control (migrate state-fn))))
  (-migrate [_ fn-fn state-fn]
    (.set mail? true)
    (a/go (a/>! control (migrate fn-fn state-fn))))
  (-effect [_ f]
    (.set mail? true)
    (a/go (a/>! control (effect f))))
  )

(comment
  (def in (a/chan 10))
  (def ctl (a/chan 10))
  (def f +)
  (def init 0)
  (def mail? (AtomicBoolean. false))
  (def p (map->ServerProcess {:in in :f f :init init :mail? mail? :control ctl}))
  (-effect p println)
  (a/>!! in 1)

  (a/>!! ctl (effect println))
  (.set mail? true)
  (a/>!! in 2)

  (a/close! in)

  )
