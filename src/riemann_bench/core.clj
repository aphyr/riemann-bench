(ns riemann-bench.core
  (:use riemann.client
        [clojure.stacktrace :only [print-cause-trace]]
        [schadenfreude.git :only [compare-versions]]
        clojure.java.shell)
  (:require [conch.core :as sh]
            [clojure.java.io :as io]
            [riemann.codec :as codec])
  (:import (java.util Queue)
           (java.lang.reflect Field)
           (java.lang ThreadLocal)
           (java.util.concurrent LinkedBlockingQueue
                                 LinkedTransferQueue
                                 ArrayBlockingQueue)))
;  (:gen-class))

(defmacro thread-local
  "Defines a new thread local initialized by evaluating body once in each
  thread. Supports (deref) as well."
  [& body]
  `(proxy [ThreadLocal clojure.lang.IDeref] []
     (initialValue []
       ~@body)

     (deref []
       (.get ~'this))))

(defn expand-path
  [^String path]
  (.getCanonicalPath (io/file path)))

(defn pid
  "Get the PID of a process. Take a step back from the Java IPC APIs, and
  literally FUCK YOUR OWN FACE."
  [p]
  (let [^Field f (.getDeclaredField (class p) "pid")]
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
   :service "riemann-bench test event"
   :state "ok"
   :description "a benchmark"
   :metric 1
   :ttl 1
   :time (/ (System/currentTimeMillis) 1000)
   :tags ["bench"]})

(defn async-tcp-run
  "Options:

  :name
  :n
  :threads
  :queue-size
  :clients
  :events"
  [opts]
  (let [msg (codec/encode-pb-msg {:events (:events opts)})]
    {:name (:name opts)
     :n (:n opts)
     :threads (:threads opts)
     :before (fn [x]
               (let [n      (:queue-size opts)
                     all-clients (atom [])
                     client (thread-local
                              (let [c (tcp-client)]
                                (swap! all-clients conj c)
                                c))
                     queue  (thread-local
                              (let [q (LinkedTransferQueue.)]
                                (dotimes [i n]
                                  (.put q (send-msg @client msg)))
                                q))]
                 [queue client all-clients]))
     :f (fn [[queue client]]
          (let [q ^Queue @queue]
          ; Block on a result
          @(.poll q)

          ; Add a new write.
          (.add q (send-msg @client msg))))
     :after (fn [[_ _ all-clients]]
              (dorun (map close! @all-clients)))}))

(def run-tcp-async-single
  (async-tcp-run
    {:name        "tcp async single"
     :n           40000000
     :threads     8
     :queue-size  128
     :events      [(example-event)]}))

(def run-tcp-async-bulk
  (async-tcp-run
    {:name        "tcp async bulk"
     :n           2000000
     :threads     8
     :queue-size  32
     :events      (take 100 (repeatedly example-event))}))

(defn suite
  "A test suite against a riemann repo in dir."
  [dir]
  {:before #(start-server dir)
   :runs [
          run-tcp-async-single
          run-tcp-async-bulk
          ]
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
