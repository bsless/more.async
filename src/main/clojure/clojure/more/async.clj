(ns clojure.more.async
  (:require
   [clojure.core.async :as a]
   [clojure.more.async.impl.pipe :as impl.pipe]))

(defn produce*
  "Puts the contents repeatedly calling f into the supplied channel.

  By default the channel will be closed if f returns nil.

  Based on clojure.core.async/onto-chan.
  Equivalent to (onto-chan ch (repeatedly f)) but cuts out the seq."
  ([ch f] (produce* ch f true))
  ([ch f close?]
   (a/go-loop []
     (let [v (f)]
       (if (and v (a/>! ch v))
         (recur)
         (when close?
           (a/close! ch)))))))

(defmacro produce
  "Execute body repeatedly in a go-loop and put its results into output ch."
  [ch & body]
  `(produce* ~ch (fn* [] ~@body)))

(defn produce-blocking*
  "Puts the contents repeatedly calling f into the supplied channel.

  By default the channel will be closed if f returns nil.

  Like `produce*` but blocking.
  Should be called inside a thread or a future."
  ([ch f]
   (produce-blocking* ch f true))
  ([ch f close?]
   (loop []
     (let [v (f)]
       (if (and v (a/>!! ch v))
         (recur)
         (when close?
           (a/close! ch)))))))

(defmacro produce-blocking
  "Execute body repeatedly in a loop and put its results into output ch.
  Like `produce*` but blocking.
  Should be called inside a thread or a future."
  [ch & body]
  `(produce-blocking* ~ch (fn* [] ~@body)))

(defn consume*
  "Takes values repeatedly from channels and applies f to them.

  The opposite of produce.

  Stops consuming values when the channel is closed."
  [ch f]
  (a/go-loop []
    (when-let [v (a/<! ch)]
      (f v)
      (recur))))

(defmacro consume
  "Takes values repeatedly from ch as v and runs body.

  The opposite of produce.

  Stops consuming values when the channel is closed."
  [ch v & body]
  `(consume* ~ch (fn* [~v] ~@body)))

(defn consume?*
  "Takes values repeatedly from channels and applies f to them.
  Recurs only when f returns a non false-y value.

  The opposite of produce.

  Stops consuming values when the channel is closed."
  [ch f]
  (a/go-loop []
    (when-let [v (a/<! ch)]
      (when (f v)
        (recur)))))

(defmacro consume?
  "Takes values repeatedly from ch as v and runs body.

  The opposite of produce.

  Stops consuming values when the channel is closed or body evaluates to a
  false-y value."
  [ch v & body]
  `(consume?* ~ch (fn* [~v] ~@body)))

(defn consume-blocking*
  "Takes values repeatedly from channels and applies f to them.

  The opposite of produce.

  Stops consuming values when the channel is closed.
  Like `consume*` but blocking."
  [ch f]
  (loop []
    (when-let [v (a/<!! ch)]
      (f v)
      (recur))))

(defmacro consume-blocking
  "Takes values repeatedly from ch as v and runs body.

  The opposite of produce.

  Stops consuming values when the channel is closed.
  Like `consume` but blocking."
  [ch v & body]
  `(consume-blocking* ~ch (fn* [~v] ~@body)))

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

(defmacro consume-blocking?
  [ch v & body]
  `(consume-blocking?* ~ch (fn* [~v] ~@body)))

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

(defn control*
  "Wraps f with control function cf such that f will be invoked when:
  cf returns a truthy value for a value taken from ctl channel or
  there is no signal on ctl channel to take immidiately"
  [f cf ctl]
  (fn []
    (if-let [sig (a/poll! ctl)]
      (when (cf sig) (f))
      (f))))

(defmacro control
  [ctl cf & body]
  `(control* (fn* [] ~@body) ~cf ~ctl))

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

