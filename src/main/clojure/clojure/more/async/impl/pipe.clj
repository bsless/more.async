(ns clojure.more.async.impl.pipe
  (:require
   [clojure.core.async :as a])
  (:import
   (clojure.core.async.impl.channels ManyToManyChannel)))

;;; https://clojure.atlassian.net/browse/ASYNC-150
(defn ooo-pipeline*
  ([n to xf from close? ex-handler type]
   (assert (pos? n))
   (let [ex-handler (or ex-handler (fn [ex]
                                     (-> (Thread/currentThread)
                                         .getUncaughtExceptionHandler
                                         (.uncaughtException (Thread/currentThread) ex))
                                     nil))
         jobs (a/chan n)
         results (a/chan n)
         process (fn [[v p :as job]]
                   (if (nil? job)
                     nil ;; closing results here would be too early
                     (let [res (a/chan 1 xf ex-handler)]
                       (a/>!! res v)
                       (a/close! res)
                       (a/put! p res)
                       true)))
         async (fn [[v p :as job]]
                 (if (nil? job)
                   nil ;; closing results here would be too early
                   (let [res (a/chan 1)]
                     (xf v res)
                     (a/put! p res)
                     true)))]
     (a/go-loop []
       (let [v (a/<! from)]
         (if (nil? v)
           (a/close! jobs)
           (do
             ;; removed the indirection and pass results as part of the job
             (a/>! jobs [v results])
             (recur)))))
     (a/go-loop []
       (let [res (a/<! results)]
         (if (nil? res)
           (when close? (a/close! to))
           (do (loop []
                 (let [v (a/<! res)]
                   (when (and (not (nil? v)) (a/>! to v))
                     (recur))))
               (recur)))))
     (a/go
       ;; ensure results is closed after all jobs have been processed
       (a/<!
        (a/merge
         (map #(case %
                 (:compute :blocking)
                 (a/thread
                   (let [job (a/<!! jobs)]
                     (when (process job)
                       (recur))))
                 :async (a/go-loop []
                          (let [job (a/<! jobs)]
                            (when (async job)
                              (recur)))))
              (repeat n type))))
       (a/close! results)))))

(defn- open!
  [^ManyToManyChannel ch]
  (reset! (.closed ch) true))

(defn eco-pipeline*
  [n to xf from close? ex-handler type]
  (assert (pos? n))
  (let [ex-handler (or ex-handler (fn [ex]
                                    (-> (Thread/currentThread)
                                        .getUncaughtExceptionHandler
                                        (.uncaughtException (Thread/currentThread) ex))
                                    nil))
        jobs (a/chan n)
        results (a/chan n)
        process (fn [res [v p :as job]]
                  (if (nil? job)
                    (do (a/close! results) nil)
                    (do
                      (a/>!! res v)
                      (a/close! res)
                      (a/put! p res)
                      true)))
        async (fn [[v p :as job]]
                (if (nil? job)
                  (do (a/close! results) nil)
                  (let [res (a/chan 1)]
                    (xf v res)
                    (a/put! p res)
                    true)))]
    (dotimes [_ n]
      (case type

        (:blocking :compute)
        (let [res (a/chan 1 xf ex-handler)]
          (a/thread
            (let [job (a/<!! jobs)]
              (when (process res job)
                (recur)))))

        :async (a/go-loop []
                 (let [job (a/<! jobs)]
                   (when (async job)
                     (recur))))))
    (a/go-loop []
      (let [v (a/<! from)]
        (if (nil? v)
          (a/close! jobs)
          (let [p (a/chan 1)]
            (a/>! jobs [v p])
            (a/>! results p)
            (recur)))))
    (a/go-loop []
      (let [p (a/<! results)]
        (if (nil? p)
          (when close? (a/close! to))
          (let [res (a/<! p)]
            (loop []
              (let [v (a/<! res)]
                (when (and (not (nil? v)) (a/>! to v))
                  (recur))))
            (open! res)
            (recur)))))))

(comment

  (defn slow
    [t]
    (map (fn [x] (Thread/sleep t) x)))

  (defn log
    [msg]
    (map (fn [x] (println msg x) x)))

  (def out
    (a/<!!
     (a/into
      []
      (let [out (a/chan 100)]
        (eco-pipeline*
         1
         out
         (comp
          (log 'stage1)
          (slow 200)
          (mapcat (fn [x] [x (inc x)]))
          (map #(* % %))
          (log 'stage2)
          (filter even?)
          (slow 200)
          (log 'stage3)
          (map inc))
         (a/to-chan (range 1))
         true
         nil
         :blocking)
        out)))))
