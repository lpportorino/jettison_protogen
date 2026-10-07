(ns gate.fixtures
  "Synthetic execution fixtures; no consumer topology or captured data."
  (:require [gate.contract :as c]
            [malli.core :as m]))

(defn example
  "Return a complete serial pair nested under one chain on a synthetic host."
  []
  {:schema/version 1
   :run {:id "demo" :source-digest (apply str (repeat 64 "a")) :complete? true
         :clock {:id "synthetic-clock" :origin-ns "9007199254740993"}
         :end-ns "100" :expected-keys ["compile" "check"]}
   :sources [{:id "capture" :kind :capture :digest (apply str (repeat 64 "b")) :label "Synthetic capture"}]
   :resources [{:id "host" :parent nil :source "capture" :kind :host :label "Synthetic host"}
               {:id "worker" :parent "host" :source "capture" :kind :process :label "Synthetic worker"}]
   :nodes [{:id "root" :key "chain" :label "Example chain" :parent nil :source "capture"
            :kind :chain :record :execution :attempt 1 :outcome :passed
            :interval {:start-ns "0" :end-ns "100"}}
           {:id "compile" :key "compile" :label "Compile" :parent "root" :source "capture"
            :kind :gate :record :execution :attempt 1 :outcome :passed
            :interval {:start-ns "0" :end-ns "40"}}
           {:id "check" :key "check" :label "Check" :parent "root" :source "capture"
            :kind :gate :record :execution :attempt 1 :outcome :passed
            :interval {:start-ns "40" :end-ns "100"}}]
   :edges [{:id "compiled-before-check" :from {:node "compile" :phase :finish}
            :to {:node "check" :phase :start} :source "capture" :kind :requires :evidence :observed}]
   :measurements []})
(m/=> example [:=> [:cat] c/Graph])

(defn decision
  "Replace the check invocation with a recorded cache decision, never a zero-duration span."
  []
  (assoc-in (example) [:nodes 2]
            {:id "check" :key "check" :label "Check" :parent "root" :source "capture"
             :kind :gate :record :decision :at-ns "40" :outcome :cached
             :reason {:code :cache-hit :detail "Synthetic matching receipt"}}))
(m/=> decision [:=> [:cat] c/Graph])

(defn measurement
  "Return measured CPU service for an explicit acquisition interval."
  []
  {:id "cpu" :node "compile" :resource "worker" :source "capture" :quantity :cpu-user-ns
   :form :delta :accounting :inclusive :interval {:start-ns "0" :end-ns "40"}
   :status :measured :value "80" :reason nil :method :wait4})
(m/=> measurement [:=> [:cat] c/Measurement])
