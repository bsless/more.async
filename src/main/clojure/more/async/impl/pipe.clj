(ns more.async.impl.pipe
  (:require
   [clojure.core.async :as a]))

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
