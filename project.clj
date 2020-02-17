(defproject bsless/more.async "0.0.3-SNAPSHOT"
  :description "More core.async abstractions"
  :url "https://github.com/bsless/more.async"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "0.7.559"]]

  :source-paths ["src/main/clojure"]
  :test-paths ["src/test/clojure"]

  :repl-options {:init-ns clojure.more.async})
