(defproject bsless/more.async "0.0.4"
  :description "More core.async abstractions"
  :url "https://github.com/bsless/more.async"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "1.0.567"]]

  :deploy-repositories [["releases" :clojars]
                        ["snapshots" :clojars]]

  :release-tasks
  [["vcs" "assert-committed"]
   ["change" "version" "leiningen.release/bump-version" "release"]
   ["vcs" "commit"]
   ["deploy" "clojars"]
   ["change" "version" "leiningen.release/bump-version"]
   ["vcs" "commit"]
   ["vcs" "push"]]

  :source-paths ["src/main/clojure"]
  :test-paths ["src/test/clojure"]

  :profiles
  {:dev {:dependencies
         [[criterium "0.4.5"]
          [metrics-clojure "2.10.0"]
          [com.clojure-goes-fast/jvm-alloc-rate-meter "0.1.3"]
          [com.clojure-goes-fast/clj-async-profiler "0.4.0"]]
         :jvm-opts ["-XX:+UnlockDiagnosticVMOptions"
                    "-XX:+DebugNonSafepoints"]
         :plugins [[lein-ancient "0.6.15"]]}}

  :repl-options {:init-ns clojure.more.async})
