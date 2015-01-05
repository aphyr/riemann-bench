(ns riemann-bench.core
  (:use riemann.client
        [clojure.stacktrace :only [print-cause-trace]]
        [schadenfreude.git :only [compare-versions]]
        clojure.java.shell)
  (:require [conch.core :as sh]
            [clojure.java.io :as io]
            [riemann.codec :as codec])
  (:import (java.util.concurrent LinkedBlockingQueue
                                 ArrayBlockingQueue)))
;  (:gen-class))

(defn expand-path
  [^String path]
  (.getCanonicalPath (io/file path)))

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

(defn await-tcp-server
  []
  (print "Waiting for server")
  (flush)
  (loop []
    (when-not (with-open [c (tcp-client)]
                (try
                  @(query c "false")
                  true
                  (catch java.io.IOException e
                    (print ".")
                    (flush)
                    false)))
      (Thread/sleep 1000)
      (recur))))

(defn start-server
  "Starts a riemann server from dir. Returns a conch Process."
  [dir]
  (let [p (system "lein" "do" "clean," "run" "--"
                  (expand-path "riemann.config") :dir dir)]
    (await-tcp-server)
    p))

(defn stop-server
  "Stop a riemann server."
  [process]
  (let [code (future (sh/exit-code process))]
    (kill-children :term (:process process))
    (println "Server exited with" @code)))


(defn example-event
  []
  {:host "test"
   :service "async tcp rate run"
   :state "ok"
   :description "a benchmark"
   :metric 1
   :ttl 1
   :time (/ (System/currentTimeMillis) 1000)
   :tags ["bench"]})

(def bulk-async-tcp-rate-run
  (let [msg (codec/encode-pb-msg
              {:events (take 100 (repeatedly example-event))})]
    {:name "bulk async tcp rate"
     :n 1000000
     :threads 48
     :before (fn [x]
               (let [n 10000
                     queue (ArrayBlockingQueue. n)
                     client (multi-client
                              (take 4 (repeatedly #(tcp-client))))]

                 ; Fill queue
                 (dotimes [i n]
                   (.put queue (send-msg client msg)))

                 [queue client]))
     :f (fn [[^ArrayBlockingQueue queue client]]
          ; Block on a result
          @(.poll queue)

          ; Add a new write.
          (.put queue (send-msg client msg)))
     :after (fn [[_ c]]
              (close! c))}))

(defn suite
  "A test suite against a riemann repo in dir."
  [dir]
  {:before #(start-server dir)
   :runs [bulk-async-tcp-rate-run]
   :after stop-server})

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
