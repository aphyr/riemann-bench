(defproject reimann-bench "1.0.0-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies [[reimann "0.0.3-SNAPSHOT"]
                 [org.clojure/clojure "1.2.0"]
                 [org.clojure/tools.logging "0.2.3"]
                ]
  :aot [reimann-bench.core]
  :main reimann-bench.core
)
