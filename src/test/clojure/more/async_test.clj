(ns more.async-test
  (:require
   [clojure.core.async :as a]
   [more.async :as sut]
   [clojure.test :as t]))

(defn ticker
  []
  (let [a (atom 0)]
    (fn []
      (swap! a inc))))

(t/deftest produce
  (let [ch (a/chan)
        f (ticker)]
    (sut/produce! ch (f))
    (t/is (= 1 (a/<!! ch)))
    (t/is (= 2 (a/<!! ch)))
    (a/close! ch)))

(t/deftest produce-blocking
  (let [ch (a/chan)
        f (ticker)]
    (a/thread
      (sut/produce!! ch (f)))
    (t/is (= 1 (a/<!! ch)))
    (t/is (= 2 (a/<!! ch)))
    (a/close! ch)))

(t/deftest consume
  (let [ch (a/chan 2)
        o (a/chan)]
    (a/>!! ch 1)
    (a/>!! ch 2)
    (a/close! ch)
    (sut/consume! ch v (a/>!! o v))
    (t/is (= 1 (a/<!! o)))
    (t/is (= 2 (a/<!! o)))))

(t/deftest consume-blocking
  (let [ch (a/chan 2)
        o (a/chan)]
    (a/>!! ch 1)
    (a/>!! ch 2)
    (a/close! ch)
    (a/thread
      (sut/consume!! ch v (a/>!! o v)))
    (t/is (= 1 (a/<!! o)))
    (t/is (= 2 (a/<!! o)))))

(t/deftest consume?
  (let [ch (a/chan 2)
        o (a/chan)]
    (a/>!! ch 1)
    (a/>!! ch 2)
    (a/close! ch)
    (sut/consume-checked! ch x (do (a/>!! o x) nil))
    (t/is (= 1 (a/<!! o)))
    (t/is (= :blocked
             (deref (future (a/<!! o)) 20 :blocked)))))

(t/deftest selecting-reductions!
  (let [in (a/chan)
        out (a/chan)
        f (fnil inc 0)
        rf (fn [acc x] (update acc x f))]
    (sut/selecting-reductions! rf {} in out)
    (t/is (= {} (a/<!! out)))
    (a/put! in :a)
    (t/is (= {:a 1} (a/<!! out)))
    (a/put! in :a)
    (t/is (= {:a 2} (a/<!! out)))
    (a/put! in :b)
    (t/is (= {:a 2 :b 1} (a/<!! out)))
    (a/close! in)
    (t/is (nil? (a/<!! out)))))

(t/deftest selecting-reductions!!
  (let [in (a/chan)
        out (a/chan)
        f (fnil inc 0)
        rf (fn [acc x] (update acc x f))]
    (a/thread
      (sut/selecting-reductions!! rf {} in out))
    (t/is (= {} (a/<!! out)))
    (a/put! in :a)
    (t/is (= {:a 1} (a/<!! out)))
    (a/put! in :a)
    (t/is (= {:a 2} (a/<!! out)))
    (a/put! in :b)
    (t/is (= {:a 2 :b 1} (a/<!! out)))
    (a/close! in)
    (t/is (nil? (a/<!! out)))))

(t/deftest batch
  (t/testing "batch blocking"
    (let [out (a/chan)
          in (a/to-chan (range 9))]
      (a/thread
        (sut/batch!! in out 3 100000 conj (constantly #{}) true))
      (t/is (= #{0 1 2} (a/<!! out)))
      (t/is (= #{3 4 5} (a/<!! out)))
      (t/is (= #{6 7 8} (a/<!! out)))
      (t/is (nil? (a/<!! out)))))
  (t/testing "batch async"
    (let [out (a/chan)
          in (a/to-chan (range 9))]
      (sut/batch! in out 3 100000 conj (constantly #{}) true)
      (t/is (= #{0 1 2} (a/<!! out)))
      (t/is (= #{3 4 5} (a/<!! out)))
      (t/is (= #{6 7 8} (a/<!! out)))
      (t/is (nil? (a/<!! out)))))
  (t/testing "batch timeout"
    (let [out (a/chan)
          f (ticker)
          in (sut/periodically! f 100)]
      (a/thread
        (sut/batch!! in out 3 220 conj (constantly #{}) true))
      (t/is (= #{1 2} (a/<!! out)))
      (t/is (= #{3 4} (a/<!! out)))
      (t/is (= #{5 6} (a/<!! out)))
      (a/close! in)
      (t/is (nil? (a/<!! out)))))
  (t/testing "empty batch"
    (let [out (a/chan)
          in (a/chan)]
      (a/thread
        (sut/batch!! in out 3 220 conj (constantly #{}) true))
      (t/is (= :timeout
               (a/alt!!
                out :batch
                (a/timeout 400) :timeout)))
      (a/close! in)
      (t/is (nil? (a/<!! out)))))
  (t/testing "batch async timeout"
    (let [out (a/chan)
          f (ticker)
          in (sut/periodically! f 100)]
      (sut/batch! in out 3 220 conj (constantly #{}) true)
      (t/is (= #{1 2} (a/<!! out)))
      (t/is (= #{3 4} (a/<!! out)))
      (t/is (= #{5 6} (a/<!! out)))
      (a/close! in)
      (t/is (nil? (a/<!! out)))))
  (t/testing "empty async batch"
    (let [out (a/chan)
          in (a/chan)]
      (a/thread
        (sut/batch!! in out 3 220 conj (constantly #{}) true))
      (t/is (= :timeout
               (a/alt!!
                 out :batch
                 (a/timeout 400) :timeout)))
      (a/close! in)
      (t/is (nil? (a/<!! out))))))

(t/deftest wait-group
  (t/testing ""
    (let [a (atom 0)
          a' (atom 0)
          f #(swap! a inc)
          cleanup #(swap! a' inc)]
      (sut/wait-group 3 (f) (f) :finally (cleanup))
      (t/is (= 6 @a))
      (t/is (= 1 @a')))))

(t/deftest round-roin!
  (t/testing ""
    (t/is
     (= [[0 3 6 9] [1 4 7] [2 5 8]]
        (mapv a/<!!
              (let [to (repeatedly 3 a/chan)
                    from (a/to-chan (range 10))]
                (sut/round-robin! from to)
                (mapv #(a/into [] %) to)))))))


(t/deftest widw-pipeline-async
  (t/testing "Pipeline async with parallelism > 1024, weak ordering guarantees"
    (let [x 4000
          out (a/chan 2000)
          coll (a/to-chan (range x))
          in (a/chan 2000)]
      (a/pipe coll in)
      (Thread/sleep 100)
      (sut/wide-pipeline-async
       4000
       1
       out
       (fn [v o] (a/go (a/<! (a/timeout 50)) (a/put! o v) (a/close! o)))
       in
       true)
      (let [res (a/<!! (a/into [] out))]
        (t/is (= x (count res)))
        (t/is (= (set (range x)) (set res)))))))
