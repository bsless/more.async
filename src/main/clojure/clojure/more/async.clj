(ns clojure.more.async
  (:require
   [clojure.core.async :as a]
   [clojure.more.impl.pipe :as impl.pipe]))

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

(defn produce-bound-blocking*
  [ch f close? pre post]
  (let [pv (pre)
        g (fn [] (f pv))]
    (produce-blocking* ch g close?)
    (post pv)))

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

(defn merge!
  [from to]
  (let [o (a/merge from)]
    (a/pipe o to)))

(defn fan!
  "Partition values from `from` by `f` and apply `xf` to each partition.
  Useful for stateful transducer operating on streaming partitioned data
  when the partitions aren't know a priori.

  Warnings and caveats:
  creates a new channel and go block per new partition.
  Very bad for unbounded inputs."
  [f xf from to]
  (let [ma (atom {})
        build-rec
        (fn [u]
          (fn [v]
            (new clojure.lang.MapEntry u v)))
        get-chan!
        (fn [v]
          (let [u (f v)]
            (or (get @ma u)
                (let [xf- (comp xf (map (build-rec u)))
                      o (a/chan 1 xf-)]
                  (swap! ma assoc u o)
                  (a/pipe o to false)
                  o))))]
    (a/go-loop []
      (let [v (a/<! from)]
        (if (nil? v)
          (let [cs (vals @ma)]
            (merge! cs to)
            (doseq [c cs] (a/close! c)))
          (let [o (get-chan! v)]
            (when (a/>! o v) (recur))))))))

(defn control
  "Wraps f with control function cf such that f will be invoked when:
  cf returns a truthy value for a value taken from ctl channel or
  there is no signal on ctl channel to take immidiately"
  [f cf ctl]
  (fn []
    (if-let [sig (a/poll! ctl)]
      (when (cf sig) (f))
      (f))))

(defmacro interrupt-controls
  [f ctl & cfs-es-ehs]
  {:pre [(zero? (rem (count cfs-es-ehs) 3))]}
  (let [catches
        (for [[cf e eh] (partition 3 cfs-es-ehs)]
          `(catch ~e t#
             (if-let [sig# (a/poll! ~ctl)]
               (when (~cf sig#) (~f))
               (~eh t#))))
        body (concat
              `(try
                 (~f))
              catches)
        fname (gensym "control__")]
    `(fn ~fname [] ~body)))

(defmacro interrupt-control
  "Like `control` but only checks ctl channel if f throws.
  takes an optional seq of exceptions and exception handlers to
  handle different exceptions which can be thrown by f."
  ([f ctl]
   (let [cf (constantly nil)]
     `(interrupt-control ~f ~cf ~ctl Throwable throw)))
  ([f cf ctl]
   `(interrupt-control ~f ~cf ~ctl Throwable throw))
  ([f cf ctl & es-ehs]
   {:pre [(zero? (rem (count es-ehs) 2))]}
   (let [cfs-es-ehs
         (mapcat (fn [[x y]] [cf x y]) (partition 2 es-ehs))]
     `(interrupt-controls ~f ~ctl ~@cfs-es-ehs))))

(defn reductions*
  "Like core/reductions, but takes elements from in channel and
  produces them to out channel."
  ([rf init in out]
   (reductions* rf init in out true))
  ([rf init in out close?]
   (a/go-loop
       [state init]
     (a/alt!
       in
       ([v]
        (if v
          (recur
           (rf state v))
          (when close?
            (a/close! out))))
       [[out state]]
       (recur state)))))

(defn do-mux
  [chans-map out]
  (let [chans (vals chans-map)
        ks (keys chans-map)
        syms (map (comp gensym name) ks)
        bindings (vec (mapcat (fn [s ch] [s `(a/<! ~ch)]) syms chans))
        check `(and ~@syms)
        out-form
        (into
         {}
         (map vector ks syms))
        loop-form
        `(a/go-loop []
           (let ~bindings
             (when ~check
               (a/>! ~out ~out-form)
               (recur))))]
    loop-form))

(defmacro mux
  [to & from]
  (assert (even? (count from)))
  (do-mux (apply hash-map from) to))

(defn batch*
  "Takes messages from in and batch them until reaching size or
  timeout ms, and puts them to out.
  Batches with reducing function rf into initial value init.
  If init is not supplied rf is called with zero args."
  ([in out size timeout rf close?]
   (batch* in out size timeout rf (rf) close?))
  ([in out size timeout rf init close?]
   (a/go-loop [n 0
               t (a/timeout timeout)
               xs init]
     (let [[v ch] (a/alts! [in t])]
       (if (= ch in)
         (if v
           (if (= n size)
             (when (a/>! out xs)
               (recur 0 (a/timeout timeout) []))
             (recur (inc n) t (rf xs v)))
           (when close? (a/close! out)))
         (when (a/>! out xs)
           (recur 0 (a/timeout timeout) [])))))))

(defn batch
  "Takes messages from in and batch them until reaching size or
  timeout ms, and puts them to out."
  ([in out size timeout]
   (batch in out size timeout true))
  ([in out size timeout close?]
   (batch* in out size timeout conj close?)))

(defn ooo-pipeline
  "Takes elements from the from channel and supplies them to the to
  channel, subject to the transducer xf, with parallelism n. Because it
  is parallel, the transducer will be applied independently to each
  element, not across elements, and may produce zero or more outputs per
  input. Outputs will be returned OUT OF ORDER. By default, the to
  channel will be closed when the from channel closes, but can be
  determined by the close? parameter. Will stop consuming the from
  channel if the to channel closes. Note this should be used for
  computational parallelism. If you have multiple blocking operations to
  put in flight, use ooo-pipeline-blocking instead, If you have multiple
  asynchronous operations to put in flight, use ooo-pipeline-async
  instead."
  ([n to xf from] (ooo-pipeline n to xf from true))
  ([n to xf from close?] (ooo-pipeline n to xf from close? nil))
  ([n to xf from close? ex-handler] (impl.pipe/ooo-pipeline* n to xf from close? ex-handler :compute)))

(defn ooo-pipeline-blocking
  "Like ooo-pipeline, for blocking operations."
  ([n to xf from] (ooo-pipeline-blocking n to xf from true))
  ([n to xf from close?] (ooo-pipeline-blocking n to xf from close? nil))
  ([n to xf from close? ex-handler] (impl.pipe/ooo-pipeline* n to xf from close? ex-handler :blocking)))

(defn ooo-pipeline-async
  "Takes elements from the from channel and supplies them to the to
  channel, subject to the async function af, with parallelism n. af must
  be a function of two arguments, the first an input value and the
  second a channel on which to place the result(s). af must close! the
  channel before returning. The presumption is that af will return
  immediately, having launched some asynchronous operation (i.e. in
  another thread) whose completion/callback will manipulate the result
  channel. Outputs will be returned OUT OF ORDER. By default, the to
  channel will be closed when the from channel closes, but can be
  determined by the close? parameter. Will stop consuming the from
  channel if the to channel closes. See also ooo-pipeline,
  ooo-pipeline-blocking."
  ([n to af from] (ooo-pipeline-async n to af from true))
  ([n to af from close?] (impl.pipe/ooo-pipeline* n to af from close? nil :async)))

(defn parking-lot
  [f n in out]
  (let [jobs (a/chan n)]
    (a/go-loop []
      (let [v (a/<! in)]
        (if (nil? v)
          (a/close! jobs)
          (let [timeout (f v)]
            (a/>! jobs (a/go
                         (a/<! (a/timeout timeout))
                         (a/>! out v)))
            (recur)))))
    (a/go
      (let [jobs (a/<! (a/reduce conj [] jobs))
            countdown (a/merge jobs)]
        (loop []
          (when (a/<! countdown)
            (recur)))
        (a/close! out)))))
