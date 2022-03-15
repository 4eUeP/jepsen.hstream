(ns jepsen.write-then-read
  (:gen-class)
  (:require [clojure.tools.logging :refer :all]
            [jepsen [db :as db] [cli :as cli] [checker :as checker]
             [client :as client] [control :as c] [generator :as gen]
             [independent :as independent] [nemesis :as nemesis]
             [tests :as tests]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.hstream.checker :as local-checker]
            [jepsen.hstream.client :refer :all]
            [jepsen.hstream.common :as common]
            [jepsen.hstream.husky :refer :all]
            [jepsen.hstream.mvar :refer :all]
            [jepsen.hstream.utils :refer :all]
            [random-string.core :as rs]
            [jepsen.hstream.nemesis :as local-nemesis])
  (:import [jepsen.hstream.common Default-Client]))

;;;;;;;;;; Global Variables ;;;;;;;;;;

(def test-stream-name-length
  "The length of random stream name. It should be a positive natural
   and not too small to avoid name confliction."
  20)

;; Read: Subscriptions & Related Data
(def subscription-timeout "The timeout of subscriptions in SECOND." 600)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn gen-adds
  [streams]
  (->> (range)
       (map (fn [x]
              {:type :invoke, :f :add, :value x, :stream (rand-nth streams)}))))
(defn gen-read
  [stream id]
  (gen/once
    {:type :invoke, :f :read, :value nil, :consumer-id id, :stream stream}))
(defn gen-sub
  [stream]
  (gen/once {:type :invoke, :f :sub, :value stream, :stream stream}))

(defn hstream-test
  "Given an options map from the command-line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (let [; "The random-named stream names. It is a vector of strings."
        test-streams (into []
                           (repeatedly (:max-streams opts)
                                       #(rs/string test-stream-name-length)))
        subscription-results
          (into [] (repeatedly (:fetching-number opts) #(ref [])))]
    (merge
      tests/noop-test
      opts
      {:pure-generators true,
       :name "HStream",
       :db (common/db-with-streams-initialized "0.7.0" test-streams),
       :client (common/Default-Client. opts
                                       subscription-results
                                       subscription-timeout),
       :nemesis (local-nemesis/nemesis+),
       :ssh {:dummy? (:dummy opts)},
       :checker (checker/compose {:set (local-checker/set+),
                                  :stat (checker/stats),
                                  :latency (checker/latency-graph),
                                  :rate (checker/rate-graph),
                                  :clock (checker/clock-plot),
                                  :exceptions (checker/unhandled-exceptions),
                                  :timeline (timeline/html)}),
       :generator
         (gen/clients
           ;; clients
           (gen/phases
             ;; 1. subscribe all streams
             (map gen-sub test-streams)
             ;; 2. randomly write to the streams
             (->> (gen-adds test-streams)
                  (gen/stagger (/ (:rate opts)))
                  (gen/time-limit (:write-time opts)))
             ;; 3. read
             ;; 3.1. ensure every stream is read
             (map #(gen-read (get test-streams %) %)
               (range 0 (count test-streams)))
             ;; 3.2. randomly distribute the left read times
             (map #(gen-read (rand-nth test-streams) %)
               (range (count test-streams) (:fetching-number opts)))
             (gen/sleep (+ 10 (:fetch-wait-time opts))))
           ;; nemesis
           (if (:nemesis-on opts)
             (->> (gen/phases (gen/sleep 10)
                              (gen/mix [(repeat {:type :info, :f :kill-node})
                                        (repeat {:type :info, :f :resume-node})]))
                  (gen/stagger (:nemesis-interval opts))
                  (gen/time-limit (+ (:write-time opts)
                                     (:fetch-wait-time opts))))
             (gen/sleep (+ (:write-time opts) (:fetch-wait-time opts)))))})))

(def cli-opts
  "Additional command line options."
  (concat
    common/cli-opts
    [["-s" "--max-streams INT"
      "The number of HStream streams to be written to in the test." :default 1
      :parse-fn read-string :validate
      [#(and (number? %) (pos? %)) "Must be a positive number"]]
     [nil "--fetching-number INT"
      "The number of fetching operations in total.
      WARNING: its value must be greater than `--max-streams`"
      :default 10 :parse-fn read-string :validate
      [#(and (number? %) (pos? %)) "Must be a positive number"]]
     ["-w" "--write-time SECOND" "The whole time to write data into database."
      :default 20 :parse-fn read-string :validate
      [#(and (number? %) (pos? %)) "Must be a positive number"]]]))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn hstream-test,
                                         :opt-spec cli-opts})
                   (cli/serve-cmd))
            args))
