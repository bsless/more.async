(ns clojure.more.async
  (:require
   [clojure.core.async :as a]))


(defn produce
  "Puts the contents repeatedly calling f into the supplied channel.

  By default the channel will be closed after the items are copied,
  but can be determined by the close? parameter.

  Returns a channel which will close after the items are copied.

  Based on clojure.core.async/onto-chan.
  Equivalent to (onto-chan ch (repeatedly f)) but cuts out the seq."
  ([ch f] (produce ch f true))
  ([ch f close?]
   (a/go-loop [v (f)]
     (if (and v (a/>! ch v))
       (recur (f))
       (when close?
         (a/close! ch))))))

(defn produce-blocking
  "Like `produce` but blocking in a thread."
  ([ch f] (produce ch f true))
  ([ch f close?]
   (a/thread
     (loop [v (f)]
       (if (and v (a/>!! ch v))
         (recur (f))
         (when close?
           (a/close! ch)))))))

(defn produce-bound-blocking
  "Like `produce-blocking`, but calls `pre` and `post` in the context
  of the thread.
  The value returned by `pre` is passed to `f` and `post`.
  `pre` is called before the loop, `post` after it exhausts.

  Useful for non thread safe objects which throw upon being accessed from
  different threads."
  [ch f close? pre post]
   (a/thread
     (let [pv (pre)]
       (loop [v (f pv)]
         (if (and v (a/>!! ch v))
           (recur (f pv))
           (when close?
             (a/close! ch))))
       (post pv))))

(defn consume
  "Takes values repeatedly from channels and applies f to them.

  The opposite of produce.

  Stops consuming values when the channel is closed."
  [ch f]
  (a/go-loop [v (a/<! ch)]
    (when v
      (f v)
      (recur (a/<! ch)))))

(defn consume?
  "Takes values repeatedly from channels and applies f to them.
  Recurs only when f returns a non false-y value.

  The opposite of produce.

  Stops consuming values when the channel is closed."
  [ch f]
  (a/go-loop [v (a/<! ch)]
    (when v
      (when (f v)
        (recur (a/<! ch))))))

(defn consume-blocking
  "Takes values repeatedly from channels and applies f to them.

  The opposite of produce.

  Stops consuming values when the channel is closed."
  [ch f]
  (a/thread
    (loop [v (a/<!! ch)]
      (when v
        (f v)
        (recur (a/<!! ch))))))

(defn consume-blocking?
  "Takes values repeatedly from channels and applies f to them.
  Recurs only when f returns a non false-y value.

  The opposite of produce.

  Stops consuming values when the channel is closed."
  [ch f]
  (a/thread
    (loop [v (a/<!! ch)]
      (when v
        (when (f v)
          (recur (a/<!! ch)))))))


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

