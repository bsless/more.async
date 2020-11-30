(ns clojure.more.request
  (:require
   [clojure.core.async :as a]
   [clojure.more.async :as a+]
   [clojure.core.async.impl.protocols :as p]
   [clojure.core.async.impl.channels :as c])
  (:import
   (java.util Comparator PriorityQueue Queue)
   (clojure.core.async.impl.channels ManyToManyChannel)))


(defprotocol IRequest
  (-serve [this]))

(defprotocol IServer
  (-request [this f]))

(defprotocol IWorker
  (-work [this]))

(defprotocol IStatus
  (-count [this]))

(deftype Request [p f]
  IRequest
  (-serve [this]
    (let [res (try (f) (catch Throwable t t))]
      (if (nil? res)
        nil
        (a/>!! p res))
      (a/close! p))))

(deftype CountedMMC [^ManyToManyChannel chan]
  c/MMC
  (cleanup [_] (.cleanup chan))
  (abort [_] (.abort chan))
  p/WritePort
  (put! [this val handler] (.put_BANG_ chan val handler))
  p/ReadPort
  (take! [this handler] (.take_BANG_ chan handler))
  p/Channel
  (closed? [_] (.closed_QMARK_ chan))
  (close! [_] (.close_BANG_ chan))
  IStatus
  (-count [_] (.count ^Queue (.buf chan))))

(defn- request!!*
  [ch f]
  (let [p (a/promise-chan)
        req (->Request p f)]
    (try
      (a/>!! ch req)
      (catch Throwable t
        (a/>!! ch t)))
    p))

(deftype Server [ch done]
  IServer
  (-request [this f] (request!!* ch f))
  java.lang.AutoCloseable
  (close [this]
    (a/close! ch) done))

(defn request!!
  [server f]
  (-request server f))

(deftype AsyncRequest [f]
  IRequest
  (-serve [this] (f)))

(defn request!*
  [ch f]
  (let [p (a/promise-chan)
        req (->AsyncRequest
             #(f (fn [resp]
                   (a/put! p resp)
                   (a/close! p))))]
    (a/go
      (try
        (a/>! ch req)
        (catch Throwable t
          (a/>! ch t)))
      (a/<! p))))

(defn request!
  [async-server af]
  (-request async-server af))

(deftype AsyncServer [ch done]
  IServer
  (-request [this f] (request!* ch f))
  java.lang.AutoCloseable
  (close [this]
    (a/close! ch) done))

(defn counted-chan
  ([buf-or-n] (CountedMMC. (a/chan buf-or-n))))

(defn counted-chan-comparator
  []
  (reify Comparator
    (compare [this o1 o2]
      (clojure.core/compare (-count o1) (-count o2)))))

(defn cpq
  [chans]
  (let [^PriorityQueue pq (PriorityQueue.
            (int (count chans))
            ^Comparator (counted-chan-comparator))]
    (doseq [ch chans]
      (.add pq ch))
    pq))

(comment
  (def ch1 (counted-chan 1))
  (def ch2 (counted-chan 1))
  (def pq (cpq [ch1 ch2]))
  (def ch' (.poll pq))
  (.offer pq ch'))

(defn worker!!
  [buf-or-n]
  (let [ch (counted-chan buf-or-n)]
    [ch (a/thread (a+/consume-blocking* ch -serve))]))

(defn worker!
  [buf-or-n]
  (let [ch (counted-chan buf-or-n)]
    [ch (a+/consume* ch -serve)]))

(defn balancer!
  [in chans]
  (let [^PriorityQueue pq (cpq chans)]
    (a/go-loop []
      (if-some [x (a/<! in)]
        (let [ch (.poll pq)]
          (a/>! ch x)
          (.offer pq ch)
          (recur))
        (doseq [ch chans]
          (a/close! ch))))))

(defn balancer!!
  [in chans]
  (let [^PriorityQueue pq (cpq chans)]
    (loop []
      (if-some [x (a/<!! in)]
        (let [ch (.poll pq)]
          (a/>!! ch x)
          (.offer pq ch)
          (recur))
        (doseq [ch chans]
          (a/close! ch))))))

(defn server!!
  [n buf-or-n]
  (let [in (a/chan buf-or-n)
        workers (mapv (fn [_] (worker!! buf-or-n)) (range n))
        chans (mapv first workers)
        tasks (mapv second workers)
        balancer (a/thread (balancer!! in chans))]
    (->Server in (conj tasks balancer))))

(defn server!
  [n buf-or-n]
  (let [in (a/chan buf-or-n)
        workers (mapv (fn [_] (worker! buf-or-n)) (range n))
        chans (mapv first workers)
        tasks (mapv second workers)
        balancer (balancer! in chans)]
    (->AsyncServer in (conj tasks balancer))))

(comment
  (def s2 (server!! 2 2))
  (.close s2)
  (-request s2 #(do (println "hello") (rand-int 10)))
  (a/poll! *1)
  (time
   (mapv
    a/<!!
    (mapv (partial request!! s2)
          (repeatedly
           10
           (constantly
            #(let [n (rand-int 10)]
               (Thread/sleep 1000)
               (println "hello" n)
               n)))))))


(comment

  (def ch (a/chan))

  (defn af
    [cb]
    (a/put! ch (rand-int 10) cb))

  (def proc (a+/consume ch v (println 'consumed v)))

  (def s1 (server! 2 2))

  (def p (request! s1 af))

  (a/<!! p)

  (.close s1)
  (a/close! ch)
  (time
   (mapv
    a/<!!
    (mapv (partial request!! s2)
          (repeatedly
           10
           (constantly
            #(let [n (rand-int 10)]
               (Thread/sleep 1000)
               (println "hello" n)
               n))))))


  )

