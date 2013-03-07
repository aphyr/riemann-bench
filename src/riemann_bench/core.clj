(ns riemann-bench.core
  (:use riemann.client
        [riemann.codec :only [map->Event]]
        [clojure.stacktrace :only [print-cause-trace]]
        [schadenfreude.git :only [compare-versions]]
        clojure.java.shell)
  (:require [conch.core :as sh])
  (:gen-class))

(defn expand-path
  [path]
  (.getCanonicalPath (java.io.File. path)))

(defn pid
  "Get the PID of a process. Take a step back from the Java IPC APIs, and
  literally FUCK YOUR OWN FACE."
  [p]
  (let [f (.. p (getClass) (getDeclaredField "pid"))]
    (.setAccessible f true)
    (.get f p)))

(defn system
  "Starts a process with cwd dir. Returns a conch process."
  [& commands]
  (let [p (apply sh/proc commands)]
    (future (sh/stream-to-out p :out))
    (future (sh/stream-to-out p :err))
    p))

(defn sh-now
  [& args]
  (sh/stream-to-string (apply sh/proc args) :out))

(defn kill-children
  "Send a signal to all children of process"
  ([process] (kill-children :term process))
  ([signal process]
   (sh/proc "pkill" "--signal" (name signal) "-P" (str (pid process)))))

(defn start-server
  "Starts a riemann server from dir. Returns a conch Process."
  [dir]
  (let [p (system "lein" "do" "clean," "run" "--" 
                  (expand-path "riemann.config") :dir dir)]
    ; Spin until started.
    (println "Waiting for server...")
    (loop []
      (if
        (try
          (close-client (tcp-client))
          true
          (catch java.net.ConnectException e false))
        (println "Server ready.")
        (do
          (Thread/sleep 100)
          (recur))))
    p))

(defn stop-server
  "Stop a riemann server."
  [process]
  (let [code (future (sh/exit-code process))]
    (kill-children :term (:process process))
    (println "Server exited with" @code)))

(defn await-tcp-server
  []
  (print "Waiting for server")
  (flush)
  (loop []
    (when-not (let [c (tcp-client)]
                (try
                  (query c "false")
                  true
                  (catch java.io.IOException e
                    (print ".")
                    (flush)
                    false)
                  (finally
                    (close-client c))))
      (Thread/sleep 100)
      (recur))))

(def drop-tcp-event-batch-run
  (let [events (take 100 (repeatedly
                          #(map->Event
                             {:host "test"
                              :service "drop tcp"
                              :state "ok"
                              :description "a benchmark"
                              :metric 1
                              :ttl 1
                              :tags ["bench"]})))]
    {:name "drop tcp event batch"
     :n 100000
     :sample 10
     :threads 10
     :before (fn [x]
               (await-tcp-server)
               (multi-client
                 (take 10 (repeatedly #(tcp-client)))))
     :f #(try
           (send-events % events)
           (catch Exception e (println e)))
     :after (fn [c]
              (close-client c))}))

(defn suite
  "A test suite against a riemann repo in dir."
  [dir]
  {:before #(start-server dir)
   :runs [;drop-tcp-event-run
          drop-tcp-event-batch-run]
   :after stop-server})

(defn suite'
  "A dumb debugging suite"
  [dir]
  {:before #(prn "setup")
   :after #(prn "teardown" %)
   :runs [{:name "hi"
           :n 1000
           :threads 1
           :before #(prn "before" %)
           :after #(prn "after" %)
           :f (fn [_] (Thread/sleep 10))}]})

(defn -main
  [dir & versions]
  (try
    (compare-versions dir versions (suite dir))
    (flush)
    (System/exit 0)
    (catch Throwable t
      (print-cause-trace t)
      (flush)
      (System/exit 1))))
