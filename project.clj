(defproject riemann-bench "1.0.0-SNAPSHOT"
  :description "Benchmark Riemann"
  :dependencies [[riemann-clojure-client "0.3.0-SNAPSHOT"]
                 [me.raynes/conch "0.4.0"]
                 [org.clojure/clojure "1.6.0"]
                 [schadenfreude "0.1.2-SNAPSHOT"]
                 [org.clojure/tools.logging "0.2.3"]
                 [org.slf4j/slf4j-log4j12 "1.7.2"]]
; :global-vars {*warn-on-reflection* true}
  :jvm-opts ["-server"
             "-Dcom.sun.management.jmxremote"
             "-XX:+UnlockCommercialFeatures"
             "-XX:+FlightRecorder"]
;  :aot [riemann-bench.core]
  :main riemann-bench.core
)
