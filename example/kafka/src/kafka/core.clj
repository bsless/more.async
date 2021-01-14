(ns kafka.core
  (:require
   [clojure.core.async :as a]
   [clojure.walk]
   [more.async :as ma]
   [flatland.useful.utils :refer [thread-local]])
  (:import [org.apache.kafka.clients.consumer ConsumerRebalanceListener KafkaConsumer OffsetCommitCallback]
           [org.apache.kafka.common.serialization ByteArrayDeserializer Deserializer StringDeserializer]
           [org.apache.kafka.common.errors WakeupException]))

(defn string-deserializer [] (StringDeserializer.))
(defn byte-array-deserializer [] (ByteArrayDeserializer.))

(defprotocol Consumer
  (poll! [c] [c ^long t])
  (close! [c])
  (subscribe! [c topics])
  (wakeup! [c]))

(defprotocol ConsumerRecord
  (checksum* [r])
  (headers* [r])
  (key* [r])
  (leader-epoch* [r])
  (offset* [r])
  (partition* [r])
  (serialized-key-size* [r])
  (serialized-value-size* [r])
  (timestamp* [r])
  (timestamp-type* [r])
  (topic* [r])
  (value* [r])
  (toString [r])
  )

(defn ->consumer
  [^KafkaConsumer c]
  (reify Consumer
    (close! [this] (.close c))
    (poll! [this] (.poll c 5000))
    (poll! [this t] (.poll c t))
    (subscribe! [this topics] (.subscribe c topics))
    (wakeup! [this] (.wakeup c))))

(defn ->record
  [^org.apache.kafka.clients.consumer.ConsumerRecord r]
  (reify ConsumerRecord
    (checksum* [this] (.checksum r))
    (headers* [this] (.headers r))
    (key* [this] (.key r))
    (leader-epoch* [this] (.leaderEpoch r))
    (offset* [this] (.offset r))
    (partition* [this] (.partition r))
    (serialized-key-size* [this] (.serializedKeySize r))
    (serialized-value-size* [this] (.serializedValueSize r))
    (timestamp* [this] (.timestamp r))
    (timestamp-type* [this] (.timestampType r))
    (topic* [this] (.topic r))
    (value* [this] (.value r))
    (toString [this] (.toString r))))


(defn new-consumer
  ([^java.util.Map config]
   (new-consumer
    (clojure.walk/stringify-keys config)
    (string-deserializer)
    (string-deserializer)))
  ([^java.util.Map config key-deserializer val-deserializer]
   (new
    KafkaConsumer
    (clojure.walk/stringify-keys config)
    key-deserializer
    val-deserializer)))

(comment

  (def c
    (->consumer
     (new-consumer {:bootstrap.servers "localhost:9092"
                    :group.id          "test-fun!"}
                   (string-deserializer)
                   (string-deserializer))))

  (subscribe! c ["inbound"])
  (def recs (poll! c))
  (def rec (first recs))

  )

(defn read-record
  [x]
  {:topic (.topic x)
   :partition (.partition x)
   :offset (.offset x)
   :key (.key x)
   :value (.value x)})

(defn create-consumer
  []
  (let [c
        (->consumer
         (new-consumer {:bootstrap.servers "localhost:9092"
                        :group.id          "test-fun!"}
                       (string-deserializer)
                       (string-deserializer)))]

    (subscribe! c ["inbound"])
    c))

(defn make-event-fn
  []
  (let [kc (thread-local (create-consumer))]
    (fn [e]
      (if e
        (.poll! ^KafkaConsumer @kc 5000)
        (do (.close @kc) nil)))))

(comment
  (def to (a/chan))
  (def from (a/chan))
  (ma/produce from (constantly true))
  (a/pipeline-blocking 1 to (map (make-event-fn)) from))

(defn make-consumer-control-fn
  [consumer]
  (fn [sig]
    (case sig
     :hello (or (println "Say hello, beautiful") [])
      :stop (close! consumer)
      (println "I shouldn't even be here today"))))

(defn build-event-fn
  [consumer control interrupt eh]
  (let [f (fn [] (poll! consumer))
        cf (make-consumer-control-fn consumer)]
    (a/go-loop []
      (when (a/<! interrupt)
        (wakeup! consumer))
      (recur))
    (ma/interrupt-control f cf control WakeupException eh Exception eh)))

#_(defn start-consumer
  [consumer-fn out interrupt control eh]
  (future
    (let [c (consumer-fn)
          f (build-event-fn c control eh)]
      (a/go-loop []
        (when (a/<! interrupt)
          (wakeup! c))
        (recur))
      (ma/produce-blocking* out f true)
      (close! c)
      (println 'DONE))))

(defn start-consumer*
  [consumer-fn out interrupt control eh]
  (future
    (ma/produce-bound-blocking*
     out
     (fn [c] (build-event-fn c control interrupt eh))
     true
     consumer-fn
     (fn [c] (close! c) (println 'DONE)))))


(comment

  (def out (a/chan 20 cat))
  (def interrupt (a/chan 1))
  (def control (a/chan 1))

  (start-consumer* create-consumer out interrupt control (fn [e] (println "Unhandled Exception" e)))

  (a/<!! out)

  (a/go (a/>!! control :hello))

  (a/>!! interrupt true)

  (a/go (a/>!! control :stop))

  (a/go (a/>! interrupt true))

  )
