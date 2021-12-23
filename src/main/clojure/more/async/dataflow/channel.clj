(ns more.async.dataflow.channel
  (:require
   [more.async.dataflow.buffer :as buffer]
   [clojure.spec.alpha :as s]
   [clojure.core.async :as a]
   [clojure.data]))

(s/def ::name (s/or :keyword keyword?
                    :string string?
                    :number number?
                    :symbol symbol?))

(s/def ::buffer (s/multi-spec buffer/-type ::buffer/type))

(s/def ::buffered-chan (s/keys :req [::name ::buffer]))

(defmulti -type ::type)

(defmethod -type ::simple [_] (s/keys :req [::name]))
(defmethod -type ::sized [_] (s/keys :req [::size ::name]))
(defmethod -type ::buffered [_] (s/keys :req [::name ::buffer]))

(s/def ::chan (s/multi-spec -type ::type))

(defmulti -compile ::type)

(defmethod -compile ::simple [_] (a/chan))
(defmethod -compile ::sized [{:keys [::size]}] (a/chan size))
(defmethod -compile ::buffered [{:keys [::buffer]}] (a/chan (buffer/-compile buffer)))

(comment
  (-compile {::type ::buffered
             ::buffer {::buffer/type ::buffer/sliding
                       ::buffer/size 2}}))

(comment

  (s/explain-data ::chan {::type ::simple
                          ::name :in})

  (s/explain-data ::type {::type ::sized
                          ::size 1
                          ::name :in})

  (s/explain-data ::type {::type ::sized
                          ::name :in
                          ::size 1})

  (s/explain-data ::buffer {::buffer/size 1
                            ::buffer/type ::buffer/blocking})

  (s/explain-data ::type {::name :out
                          ::type ::buffered
                          ::buffer
                          {::buffer/size 1
                           ::buffer/type ::buffer/blocking}}))
