(ns clojure.more.async-test
  (:require
   [clojure.core.async :as a]
   [clojure.more.async :as sut]
   [clojure.test :as t]))

(defn ticker
  []
  (let [a (atom 0)]
    (fn []
      (swap! a inc))))

(t/deftest produce
  (let [ch (a/chan)
        f (ticker)]
    (sut/produce ch f)
    (t/is (= 1 (a/<!! ch)))
    (t/is (= 2 (a/<!! ch)))
    (a/close! ch)))

(t/deftest produce-blocking
  (let [ch (a/chan)
        f (ticker)]
    (sut/produce-blocking ch f)
    (t/is (= 1 (a/<!! ch)))
    (t/is (= 2 (a/<!! ch)))
    (a/close! ch)))

(t/deftest consume
  (let [ch (a/chan 2)
        o (a/chan)]
    (a/>!! ch 1)
    (a/>!! ch 2)
    (a/close! ch)
    (sut/consume ch #(a/>!! o %))
    (t/is (= 1 (a/<!! o)))
    (t/is (= 2 (a/<!! o)))))

(t/deftest consume-blocking
  (let [ch (a/chan 2)
        o (a/chan)]
    (a/>!! ch 1)
    (a/>!! ch 2)
    (a/close! ch)
    (sut/consume-blocking ch #(a/>!! o %))
    (t/is (= 1 (a/<!! o)))
    (t/is (= 2 (a/<!! o)))))

(t/deftest consume?
  (let [ch (a/chan 2)
        o (a/chan)]
    (a/>!! ch 1)
    (a/>!! ch 2)
    (a/close! ch)
    (sut/consume? ch (fn [x] (a/>!! o x) nil))
    (t/is (= 1 (a/<!! o)))
    (t/is (= :blocked
             (deref (future (a/<!! o)) 20 :blocked)))))
