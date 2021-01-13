(ns more.async.lab.pipe
  (:require
   [clojure.core.async :as a]
   [clojure.more.async.lab.channels :refer [reopen!]]))

(defn recycle-ooo-pipeline*
  ([n to xf from close? ex-handler type]
   (assert (pos? n))
   (let [ex-handler (or ex-handler (fn [ex]
                                     (-> (Thread/currentThread)
                                         .getUncaughtExceptionHandler
                                         (.uncaughtException (Thread/currentThread) ex))
                                     nil))
         ;; nchans (* 3 n)
         nchans (inc (* 2 n))
         chans (a/chan nchans)
         jobs (a/chan n)
         results (a/chan n)
         process (fn [[v p :as job]]
                   (if (nil? job)
                     nil ;; closing results here would be too early
                     (let [res (a/<!! chans)]
                       (a/>!! res v)
                       (a/close! res)
                       (a/put! p res)
                       true)))
         async (fn [[v p :as job]]
                 (if (nil? job)
                   nil ;; closing results here would be too early
                   (let [res (a/<!! chans)]
                     (xf v res)
                     (a/put! p res)
                     true)))]
     (dotimes [_ nchans]
       (a/put! chans (if (= type :async)
                       (a/chan 1)
                       (a/chan 1 xf ex-handler))))
     (a/go
       ;; ensure results is closed after all jobs have been processed
       (a/<! (a/merge
              (map #(case %
                      (:compute :blocking) (a/thread
                                             (let [job (a/<!! jobs)]
                                               (when (process job)
                                                 (recur))))
                      :async (a/go-loop []
                               (let [job (a/<! jobs)]
                                 (when (async job)
                                   (recur)))))
                   (repeat n type))))
       (a/close! results))
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
           (do
             (loop []
               (let [v (a/<! res)]
                 (when (and (not (nil? v)) (a/>! to v))
                   (recur))))
             (reopen! res)
             (a/put! chans res)
             (recur))))))))

(defn recycle-pipeline*
  ([n to xf from close? ex-handler type]
     (assert (pos? n))
     (let [ex-handler (or ex-handler (fn [ex]
                                       (-> (Thread/currentThread)
                                           .getUncaughtExceptionHandler
                                           (.uncaughtException (Thread/currentThread) ex))
                                       nil))
           chan (fn []
                  (if (= type :async)
                        (a/chan 1)
                        (a/chan 1 xf ex-handler)))
           jobs (a/chan n)
           results (a/chan n)
           process
           (fn [res]
            (fn [[v p :as job]]
              (if (nil? job)
                (do (a/close! results) nil)
                (do
                  (reopen! res)
                  (a/>!! res v)
                  (a/close! res)
                  (a/put! p res)
                  true))))
           async (fn [res]
                   (fn [[v p :as job]]
                     (if (nil? job)
                       (do (a/close! results) nil)
                       (do
                         (reopen! res)
                         (xf v res)
                         (a/put! p res)
                         true))))]
       (dotimes [_ n]
         (case type
           (:blocking :compute)
           (let [res (chan)
                 process (process res)]
             (println 'HERE)
             (a/thread
               (let [job (a/<!! jobs)]
                 (when (process job)
                   (recur)))))
           :async
           (let [res (chan)
                 async (async res)]
             (a/go-loop []
               (let [job (a/<! jobs)]
                 (when (async job)
                   (recur)))))))
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
                        (recur))))))))
