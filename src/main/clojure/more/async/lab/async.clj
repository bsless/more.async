(ns more.async.lab.async
  (:require
   [clojure.core.async :as a]))


(defn parking-lot
  "Takes elements from the in channel applies f to them and times them
  out for the duration returned by f, subsequently producing them to out
  channel."
  ([n out f in]
   (parking-lot n out f in true))
  ([n out f in close?]
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
     (when close?
       (a/go
         (let [jobs (a/<! (a/reduce conj [] jobs))
               countdown (a/merge jobs)]
           (loop []
             (when (a/<! countdown)
               (recur)))
           (a/close! out)))))))

(defn truck-stop
  ([n out f in]
   (let [bpc (a/chan n)
         io [in out]
         sentinel true]
     (a/go-loop [chs []]
       (let [[v c] (a/alts! io)]
         (if (= c in)
           (if (nil? v) (a/close! bpc)
               (let [timeout (f v)
                     ch (a/chan 1)]
                 (a/go
                   (a/>! bpc sentinel)
                   (a/<! (a/timeout timeout))
                   (a/put! ch v)
                   (a/close! ch))
                 (recur (conj chs ch))))
           (if (pos? (count chs))
             (let [[v c] (a/alts! chs)]
               (if (nil? v)
                 (recur (filterv #(not= c %) chs))
                 (do
                   (a/>! out v)
                   (when (nil? (a/<! bpc))
                     (a/close! out)
                     nil)
                   (recur (filterv #(not= c %) chs)))))
             (recur chs))))))))
