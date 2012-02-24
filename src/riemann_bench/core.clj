(ns riemann-bench.core
  (:use riemann.client)
  (:use riemann.logging)
  (:use riemann.common)
  (:use clojure.tools.logging)
  (:gen-class))

(riemann.logging/init)

(defmacro rtime
    "Evaluates expr and returns the time it took in seconds"
    [expr]
    `(let [start# (. System (nanoTime))
                    ret# ~expr]
            (/ (- (. System (nanoTime)) start#) 1000000000.0)))

(defn -main
  [& argv]
    (info "here")

  (let [c (riemann.client/tcp-client)
        n 1000
        threads 100
        events (take n (repeatedly 
                         (fn []
                           {:host "test"
                            :service "bench"
                            :state "ok"
                            :description "Yo, I'm text!"
                            :metric 1.23
                            :ttl 1
                            :tags ["bench"]})))]

    (info 
      (rtime
        (threaded threads
                  (let [client (tcp-client)]
                    (doseq [e events] (send-event c e))
                    (close-client client)))))

    (info (query c "true"))
    (close-client c))
  (System/exit 0))
