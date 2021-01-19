(ns more.async
  (:refer-clojure :exclude [merge])
  (:require
   [clojure.core.async :as a]
   [more.async.impl.pipe :as impl.pipe]))

(alias 'core 'clojure.core)

(defn put-recur!*
  "Repeatedly [[a/put!]] into `ch` the results of invoking `f`.
  All limitations which apply to [[a/put!]] apply here as well.
  Uses the core.async fixed size dispatch thread pool."
  [ch f]
  (when-some [v (f)]
    (a/put! ch v (fn loop-fn [success?]
                   (when success?
                     (when-some [v (f)]
                       (a/put! ch v loop-fn)))))))

(defmacro put-recur!
  "Repeatedly [[a/put!]] into `ch` the results of running `body`.
  All limitations which apply to [[a/put!]] apply here as well.
  Uses the core.async fixed size dispatch thread pool."
  [ch & body]
  `(put-recur!* ~ch (fn* [] ~@body)))

(defn produce-call!
  "Put the contents repeatedly calling `f` into the supplied channel.

  By default the channel will be closed if f returns nil."
  ([ch f] (produce-call! ch f true))
  ([ch f close?]
   (a/go-loop []
     (let [v (f)]
       (if (and (some? v) (a/>! ch v))
         (recur)
         (when close?
           (a/close! ch)))))))

(defmacro produce!
  "Execute `body` repeatedly in a go-loop and put its results into
  output `ch`."
  [ch & body]
  `(produce-call! ~ch (fn* [] ~@body)))

(defn produce-call!!
  "Put the contents of repeatedly calling `f` into the supplied channel
  `ch`.

  By default the channel will be closed if f returns nil.

  Like [[produce-call!]] but blocking.
  Should be called inside a thread or a future."
  ([ch f]
   (produce-call!! ch f true))
  ([ch f close?]
   (loop []
     (let [v (f)]
       (if (and (some? v) (a/>!! ch v))
         (recur)
         (when close?
           (a/close! ch)))))))

(defmacro produce!!
  "Execute body repeatedly in a loop and put its results into output `ch`.
  Like [[produce!]] but blocking.
  Should be called inside a thread or a future."
  [ch & body]
  `(produce-call!! ~ch (fn* [] ~@body)))

(defn take-recur!*
  "Repeatedly [[a/take!]] from `ch` and apply `f` to the consumed value.
  All limitations which apply to [[a/take!]] apply here as well.
  Stops recurring when the channel is closed.
  Uses the core.async fixed size dispatch thread pool."
  [ch f]
  (a/take! ch (fn loop-fn [v]
                (when (some? v)
                  (f v)
                  (a/take! ch loop-fn)))))

(defmacro take-recur!
  "Repeatedly [[a/take!]] from `ch` as `v` and evaluate `body` with it.
  All limitations which apply to [[a/take!]] apply here as well.
  Stops recurring when the channel is closed.
  Uses the core.async fixed size dispatch thread pool."
  [ch v & body]
  `(take-recur!* ~ch (fn* [~v] ~@body)))

(defn consume-call!
  "Take values repeatedly from `ch` and apply `f` to them.

  The opposite of produce.

  Stops consuming values when the channel is closed."
  [ch f]
  (a/go-loop []
    (when-some [v (a/<! ch)]
      (f v)
      (recur))))

(defmacro consume!
  "Takes values repeatedly from `ch` as `v` and evaluate `body` with it.

  The opposite of produce.

  Stops consuming values when the channel is closed."
  [ch v & body]
  `(consume-call! ~ch (fn* [~v] ~@body)))

(defn consume-checked-call!
  "Take values repeatedly from `ch` and apply `f` to them.
  Recur only when `f` returns a non false-y value.

  The opposite of produce.

  Stop consuming values when the channel is closed."
  [ch f]
  (a/go-loop []
    (when-some [v (a/<! ch)]
      (when (f v)
        (recur)))))

(defmacro consume-checked!
  "Take values repeatedly from `ch` as `v` and run `body`.

  The opposite of produce.

  Stops consuming values when the channel is closed or body evaluates to a
  false-y value."
  [ch v & body]
  `(consume-checked-call! ~ch (fn* [~v] ~@body)))

(defn consume-call!!
  "Take values repeatedly from `ch` and applies `f` to them.

  The opposite of produce.

  Stops consuming values when the channel is closed.
  Like [[consume-call!]] but blocking."
  [ch f]
  (loop []
    (when-some [v (a/<!! ch)]
      (f v)
      (recur))))

(defmacro consume!!
  "Take values repeatedly from `ch` as `v` and evaluate `body` with it.

  The opposite of produce.

  Stops consuming values when the channel is closed.
  Like [[consume!]] but blocking."
  [ch v & body]
  `(consume-call!! ~ch (fn* [~v] ~@body)))

(defn consume-checked-call!!
  "Takes values repeatedly from `ch` and applies `f` to them.
  Recurs only when `f` returns a non false-y value.

  The opposite of produce.

  Stops consuming values when the channel is closed.
  Like [[consume-checked-call!]] but blocking."
  [ch f]
  (loop []
    (when-some [v (a/<!! ch)]
      (when (f v)
        (recur)))))

(defmacro consume-checked!!
  "Takes values repeatedly from `ch` as `v` and evaluate `body`.
  Recurs only when `body` evaluates to a non false-y value.

  The opposite of produce.

  Stops consuming values when the channel is closed.
  Like [[consume-checked!]] but blocking."
  [ch v & body]
  `(consume-checked-call!! ~ch (fn* [~v] ~@body)))

(defn split!
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

(defn split?!
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

(defn merge
  "Like [[clojure.core.async/merge]] but pipes all channels to `to`."
  ([from to]
   (merge from to))
  ([from to close?]
   (let [o (a/merge from)]
     (a/pipe o to close?))))

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
            (merge cs to)
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

(defn reductions!
  "Like core/reductions, but takes elements from in channel and
  produces them to out channel."
  ([rf init in out]
   (reductions! rf init in out true))
  ([rf init in out close?]
   (a/go-loop [state init]
     (let [[v ch] (a/alts! [in [out state]])]
       (if (identical? ch in)
         (if v
           (recur (rf state v))
           (when close? (a/close! out)))
         (recur state))))))

(defn reductions!!
  "Like core/reductions, but takes elements from in channel and
  produces them to out channel."
  ([rf init in out]
   (reductions!! rf init in out true))
  ([rf init in out close?]
   (loop [state init]
     (let [[v ch] (a/alts!! [in [out state]])]
       (if (identical? ch in)
         (if v
           (recur (rf state v))
           (when close? (a/close! out)))
         (recur state))))))

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
  "Put into `to` a map of takes by all pairs in `from` from key to
  channel."
  [to & from]
  (assert (even? (count from)))
  (do-mux (apply hash-map from) to))

(defn- ex-handler [ex]
  (-> (Thread/currentThread)
      .getUncaughtExceptionHandler
      (.uncaughtException (Thread/currentThread) ex))
  nil)

(defn periodically!
  "Invoke `f` periodically with period `t` and put the results into
  returned channel.

  Optionally takes buffer or buffer size and exception handler for `f`."
  ([f t]
   (periodically! f t nil))
  ([f t buf-or-n]
   (periodically! f t buf-or-n ex-handler))
  ([f t buf-or-n exh]
   (assert (and (number? t) (pos? t)))
   (let [o (a/chan buf-or-n)
         f #(try (f) (catch Exception e (exh e)))]
     (a/go-loop [t' (a/timeout t)]
       (let [[_ ch] (a/alts! [t' o])]
         (if (identical? ch t')
           (when-some [v (f)]
             (when (a/>! o v)
               (recur (a/timeout t))))
           (recur t'))))
     o)))

(comment
 (def out (periodically! (constantly 1) 1000))
 (consume! out v (println v))
 (a/close! out))

(defn- assert-valid-batch
  [size timeout]
  (assert (number? size))
  (assert (number? timeout))
  (assert (pos? size))
  (assert (pos? timeout)))

(defn batch!!
  "Takes messages from `in` and batch them until reaching `size` or
  `timeout` ms, and puts them to `out`.
  Batches with reducing function `rf` into initial value derived from calling `init`.
  If init is not supplied rf is called with zero args.
  By default will use conj and a vector.
  Like [[batch!]] but blocking."
  ([in out size timeout]
   (batch!! in out size timeout conj))
  ([in out size timeout rf]
   (batch!! in out size timeout rf rf))
  ([in out size timeout rf init]
   (batch!! in out size timeout rf init true))
  ([in out size timeout rf init close?]
   (assert-valid-batch size timeout)
   (let [size (long size)
         timeout (long timeout)]
     (loop [n 0
            xs (init)
            t (a/timeout timeout)]
       (let [[v ch] (a/alts!! [in t])]
         (if (identical? ch in)
           (if (nil? v)
             (do
               (when (pos? n) (a/>!! out xs))
               (when close? (a/close! out)))
             (let [n (unchecked-inc n)
                   xs (rf xs v)]
               (if (== n size)
                 (do (a/>!! out xs)
                     (recur 0 (init) (a/timeout timeout)))
                 (recur n xs t))))
           (if (pos? n)
             (when (a/>!! out xs) (recur 0 (init) (a/timeout timeout)))
             (recur n xs (a/timeout timeout)))))))))

(comment
  (def out (a/chan))
  (consume! out v (println v))
  (def in (a/to-chan (range 4)))
  (batch!! in out 2 100000 conj (constantly #{}) true))

(comment
  (def out (a/chan))
  (consume! out v (println v))
  (def in (periodically! (constantly 1) 1000 1))
  (a/thread
    (batch!! in out 3 2000 conj (constantly []) true))
  (a/close! in))

(defn batch!
  "Takes messages from `in` and batch them until reaching `size` or
  `timeout` ms, and puts them to `out`.
  Batches with reducing function `rf` into initial value derived from calling `init`.
  If init is not supplied rf is called with zero args.
  By default will use conj and a vector."
  ([in out size timeout]
   (batch! in out size timeout conj))
  ([in out size timeout rf]
   (batch! in out size timeout rf rf))
  ([in out size timeout rf init]
   (batch! in out size timeout rf init true))
  ([in out size timeout rf init close?]
   (assert-valid-batch size timeout)
   (let [size (long size)
         timeout (long timeout)]
     (a/go-loop
         [n 0
          xs (init)
          t (a/timeout timeout)]
       (let [[v ch] (a/alts! [in t])]
         (if (identical? ch in)
           (if (nil? v)
             (do
               (when (pos? n) (a/>! out xs))
               (when close? (a/close! out)))
             (let [nn (unchecked-inc n)
                   nxs (rf xs v)]
               (if (== nn size)
                 (do (a/>! out nxs)
                     (recur 0 (init) (a/timeout timeout)))
                 (recur nn nxs t))))
           (if (pos? n)
             (do (a/>! out xs) (recur 0 (init) (a/timeout timeout)))
             (recur n xs (a/timeout timeout)))))))))

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

(defn- noop
  ([])
  ([_]))

(defn wait*
  "Wait for `tasks`, a collection of channels, to finish.
  Returns nothing meaningful."
  ([tasks mode]
   (let [o (a/merge tasks)]
     (case mode
       :blocking (consume-call!! o noop)
       :non-blocking (consume-call! o noop)))))

(defn wait!
  "Wait for `tasks`, a collection of channels, to finish in a
  non-blocking context.
  Returns nothing meaningful."
  [tasks]
  (wait* tasks :non-blocking))

(defn wait!!
  "Wait for `tasks`, a collection of channels, to finish in a
  blocking context.
  Returns nothing meaningful."
  [tasks]
  (wait* tasks :blocking))

(defn- wrap-cleanup
  [n cleanup]
  (let [a (atom n)
        p (a/chan)]
    [p (fn []
         (when (zero? (swap! a dec))
           (cleanup)
           (a/close! p)))]))

(defn wait-group-call
  "Run `f` `n` times in [[a/thread]] and wait for all runs to finish.
  Returns a promise chan which closes when all tasks finish.
  May run `cleanup` in the end. Cleanup is guaranteed to run once."
  ([n f]
   (wait-group-call n f noop))
  ([n f cleanup]
   (let [[p cleanup] (wrap-cleanup n cleanup)]
     (mapv
      (fn [_]
        (a/thread (f) (cleanup)))
      (range n))
     p)))

(comment
  (a/<!!
   (wait-group-call
    8
    (fn []
      (let [n (+ 1000 (rand-int 1000))]
        (Thread/sleep n)
        (println n)))
    (fn []
      (println "goodbye!")))))

(defmacro wait-group
  "Run `body` `n` times in [[a/thread]] and wait for all runs to finish.
  Returns a promise chan which closes when all tasks finish. May run
  `cleanup` in the end. Cleanup is delimited from the rest of the body
  by the keyword `:finally`.
  Cleanup is guaranteed to run once.

  Example:
  ```clojure
  (wait-group
   8
   (let [n (+ 1000 (rand-int 1000))]
     (Thread/sleep n)
     (println n))
   :finally
   (println \"goodbye!\"))
  ```
  "
  [n & body]
  (let [p (partial identical? :finally)]
    (assert (<= (count (filter p body)) 1))
    (let [[body _ finally] (partition-by p body)]
      `(wait-group-call ~n (fn* [] ~@body) (fn* [] ~@finally)))))

(comment
  (wait-group
   8
   (let [n (+ 1000 (rand-int 1000))]
     (Thread/sleep n)
     (println n))
   :finally
   (println "goodbye!")))
