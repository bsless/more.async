(defproject kafka "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "0.5.527"]
                 [bsless/more.async "0.0.2-alpha"]
                 [org.flatland/useful "0.11.6"]
                 [org.apache.kafka/kafka-clients "2.3.0"]]
  :profiles
  {:dev {:dependencies [[criterium "0.4.5"]
                        [com.clojure-goes-fast/clj-memory-meter "0.1.0"]]}}
  :repl-options {:init-ns kafka.core})
