(ns more.async.dataflow.buffer
  (:require
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]))


(s/def ::size int?)

(s/def ::fixed-buffer (s/keys :req [::size]))

(defmulti  -type ::type)
(defmethod -type ::blocking [_] (s/keys :req [::size]))
(defmethod -type ::sliding  [_] (s/keys :req [::size]))
(defmethod -type ::dropping [_] (s/keys :req [::size]))

(defmulti -compile ::type)
(defmethod -compile ::blocking [{:keys [::size]}] (a/buffer size))
(defmethod -compile ::sliding  [{:keys [::size]}] (a/sliding-buffer size))
(defmethod -compile ::dropping [{:keys [::size]}] (a/dropping-buffer size))

(comment
  (-compile {::type ::blocking
             ::size 1})
  (s/explain-data ::type {::type ::simple
                          ::name :in}))
