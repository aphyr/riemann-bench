(ns riemann-bench.core
  (:use riemann.client
        [riemann.codec :only [map->Event]]
        [clojure.stacktrace :only [print-cause-trace]]
        [schadenfreude.git :only [compare-versions]]
        clojure.java.shell)
  (:require [conch.core :as sh])
  (:import (java.util.concurrent LinkedBlockingQueue
                                 ArrayBlockingQueue))
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
      (Thread/sleep 500)
      (recur))))

(defn example-event
  []
  (map->Event
    {:host "test"
     :service "async tcp rate run"
     :state "ok"
     :description "a benchmark"
     :metric 1
     :ttl 1
     :tags ["bench"]}))

(def bulk-async-tcp-rate-run
  (let [events (take 100 (repeatedly #(example-event)))]
    {:name "bulk async tcp rate"
     :n 200000
     :sample 10
     :threads 4
     :before (fn [x]
               (await-tcp-server)
               (let [n 5000
                     queue (ArrayBlockingQueue. n)
;                     client (multi-client
;                              (take 2 (repeatedly #(tcp-client))))]
                     client (tcp-client)]
                 ; Fill queue
                 (dotimes [i n]
                   (.put queue (doto (promise) (deliver nil))))
                 [queue client]))
     :f (fn [[queue client]]
          (try 
            ; Block on a result
            (deref (.poll queue))

            ; Add a new write.
            (.put queue (async-send-events client events))
            (catch Exception e (println e))))
     :after (fn [[queue c]]
              (close-client c))}))

(def async-tcp-rate-run
  (let [event (example-event)]
    {:name "async tcp rate"
     :n 10000000
     :sample 100
     :threads 3
     :before (fn [x]
               (await-tcp-server)
               (let [n 5000
                     queue (ArrayBlockingQueue. n)
;                     client (multi-client
;                              (take 2 (repeatedly #(tcp-client))))]
                     client (tcp-client)]
                 ; Fill queue
                 (dotimes [i n]
                   (.put queue (doto (promise) (deliver nil))))
                 [queue client]))
     :f (fn [[queue client]]
          (try 
            ; Block on a result
            (deref (.poll queue))

            ; Add a new write.
            (.put queue (async-send-event client event))
            (catch Exception e (println e))))
     :after (fn [[queue c]]
              (close-client c))}))

(defn suite
  "A test suite against a riemann repo in dir."
  [dir]
  {:before #(start-server dir)
   :runs [;drop-tcp-event-run
          ; async-tcp-rate-run
          bulk-async-tcp-rate-run]
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
