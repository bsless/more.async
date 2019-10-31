(ns clojure.more.async
  (:require
   [clojure.core.async :as a]))

(defn produce
  "Puts the contents repeatedly calling f into the supplied channel.

  By default the channel will be closed if f returns nil.

  Based on clojure.core.async/onto-chan.
  Equivalent to (onto-chan ch (repeatedly f)) but cuts out the seq."
  ([ch f] (produce ch f true))
  ([ch f close?]
   (a/go-loop []
     (let [v (f)]
       (if (and v (a/>! ch v))
         (recur)
         (when close?
           (a/close! ch)))))))

(defn produce-blocking*
  "Like `produce` but blocking."
  ([ch f close?]
   (loop []
     (let [v (f)]
       (if (and v (a/>!! ch v))
         (recur)
         (when close?
           (a/close! ch)))))))

(defn produce-blocking
  "Like `produce-blocking*` but takes a thread."
  ([ch f] (produce ch f true))
  ([ch f close?]
   (a/thread (produce-blocking* ch f close?))))

(defn produce-bound-blocking
  "Like `produce-blocking`, but calls `pre` and `post` in the context
  of the thread.
  The value returned by `pre` is passed to `f` and `post`.
  `pre` is called before the loop, `post` after it exhausts.

  Useful for non thread safe objects which throw upon being accessed from
  different threads."
  [ch f close? pre post]
   (a/thread
     (let [pv (pre)
           g (fn [] (f pv))]
       (produce-blocking* ch g close?)
       (post pv))))

(defn consume
  "Takes values repeatedly from channels and applies f to them.

  The opposite of produce.

  Stops consuming values when the channel is closed."
  [ch f]
  (a/go-loop []
    (when-let [v (a/<! ch)]
      (f v)
      (recur))))

(defn consume?
  "Takes values repeatedly from channels and applies f to them.
  Recurs only when f returns a non false-y value.

  The opposite of produce.

  Stops consuming values when the channel is closed."
  [ch f]
  (a/go-loop []
    (when-let [v (a/<! ch)]
      (when (f v)
        (recur)))))

(defn consume-blocking*
  "Takes values repeatedly from channels and applies f to them.

  The opposite of produce.

  Stops consuming values when the channel is closed.
  Like `consume` but blocking."
  [ch f]
   (loop []
     (when-let [v (a/<!! ch)]
       (f v)
       (recur))))

(defn consume-blocking?*
  " Takes values repeatedly from channels and applies f to them.
  Recurs only when f returns a non false-y value.

  The opposite of produce.

  Stops consuming values when the channel is closed.
  Like `consume?` but blocking."
  [ch f]
   (loop []
     (when-let [v (a/<!! ch)]
       (when (f v)
         (recur)))))

(defn consume-blocking
  "Runs `consume-blocking*` in s thread."
  [ch f]
  (a/thread (consume-blocking* ch f)))

(defn consume-blocking?
  "Runs `consume-blocking?*` in s thread."
  [ch f]
  (a/thread (consume-blocking?* ch f)))

(defn split*
  "Takes a channel, function f :: v -> k and a map of keys to channels k -> ch,
  routing the values v from the input channel to the channel such that
  (f v) -> ch.

  (get m (f v)) must be non-nil for every v! "
  ([f ch m]
   (a/go-loop []
     (let [v (a/<! ch)]
       (if (nil? v)
         (doseq [c (vals m)] (a/close! c))
         (if-let [o (get m (f v))]
           (when (a/>! o v)
             (recur))
           (throw (Exception. "Channel does not exist"))))))))

(defn split-maybe
  "Takes a channel, function f :: v -> k and a map of keys to channels k -> ch,
  routing the values v from the input channel to the channel such that
  (f v) -> ch.

  If (f v) is not in m, the value is dropped"
  ([f ch m]
   (a/go-loop []
     (let [v (a/<! ch)]
       (if (nil? v)
         (doseq [c (vals m)] (a/close! c))
         (if-let [o (get m (f v))]
           (when (a/>! o v)
             (recur))
           (recur)))))))

