(ns gate.view.fixtures
  "Public synthetic branch/join, dense-child and resource evidence, never imported captures."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.fixtures :as fixtures]
            [gate.report :as report]
            [gate.viewer-asset :as asset]
            [malli.core :as m]))

(defn task
  "Create a synthetic execution in nanoseconds from millisecond fixture boundaries."
  [id parent start end]
  {:id id :key id :label id :parent parent :source "capture" :kind (if (= id "root") :chain :gate)
   :record :execution :attempt 1 :outcome :passed
   :interval {:start-ns (str (* start 1000000)) :end-ns (str (* end 1000000))}})
(m/=> task [:=> [:cat c/Id [:maybe c/Id] [:int {:min 0 :max 1000}] [:int {:min 0 :max 1000}]] c/Node])

(defn branch
  "A 260 ms branch/join; B/C span 140 ms and sum to 220 ms."
  []
  (let [nodes [(task "root" nil 0 260) (task "A-build" "root" 0 100)
               (task "B-check" "root" 100 180) (task "C-check" "root" 100 240) (task "D-package" "root" 240 260)]]
    (-> (fixtures/example)
        (assoc-in [:run :end-ns] "260000000")
        (assoc-in [:run :expected-keys] (mapv :key (rest nodes)))
        (assoc :nodes nodes :edges (mapv (fn [index [a b]] {:id (str "edge-" index) :from {:node a :phase :finish}
                                                            :to {:node b :phase :start} :kind :requires :evidence :observed :source "capture"})
                                         (range) [["A-build" "B-check"] ["A-build" "C-check"] ["B-check" "D-package"] ["C-check" "D-package"]])))))
(m/=> branch [:=> [:cat] c/Graph])

(defn dense
  "Create 3,000 small tasks, 2,960 child dependencies, a hidden failure and a hostile literal label."
  []
  (let [children (mapv (fn [i] (assoc (task (str "child-" i) "B-check" (+ 100 (mod i 75)) (+ 101 (mod i 75)))
                                      :kind :test :outcome (if (= i 1500) :failed :passed))) (range 3000))]
    (-> (branch)
        (update :nodes into (assoc-in children [0 :label] "Literal </script><script>window.injected=true</script> 🧪 long label with concurrent subchecks"))
        (update :edges into (for [i (range 2999) :when (< (mod i 75) 74)]
                              {:id (str "child-edge-" i) :from {:node (str "child-" i) :phase :finish}
                               :to {:node (str "child-" (inc i)) :phase :start} :kind :requires :evidence :observed :source "capture"})))))
(m/=> dense [:=> [:cat] c/Graph])

(defn signals
  "Add 32 logical core resources, host memory/CPU/PSI, GPU and device I/O with honest raw units."
  []
  (let [resources (into (:resources (branch))
                        (concat (for [i (range 32)] {:id (str "core-" i) :parent "host" :source "capture" :kind :logical :label (str "CPU core " i)})
                                [{:id "gpu" :parent "host" :source "capture" :kind :gpu :label "GPU 0"}
                                 {:id "disk" :parent "host" :source "capture" :kind :logical :label "Disk 0"}]))
        tracks (concat [["host" :cpu-user-ns :delta] ["host" :memory-current-bytes :gauge] ["host" :pressure-io-some-us :delta]
                        ["gpu" :gpu-utilization-percent :gauge] ["gpu" :memory-current-bytes :gauge] ["disk" :bytes-read :delta]]
                       (for [i (range 32)] [(str "core-" i) :cpu-user-ns :delta]))
        samples (vec (for [[r q form] tracks i (range 65)]
                       {:id (str r "-" (name q) "-" i) :node nil :resource r :source "capture" :quantity q :form form :accounting :shared
                        :interval {:start-ns (str (* i 4000000)) :end-ns (str (* (inc i) 4000000))}
                        :status (if (= i 59) :unavailable :measured) :value (when-not (= i 59) (str (if (= q :gpu-utilization-percent) (+ 20 (mod (* i 5) 70)) (* (cond (<= 25 i 30) 1 (<= 45 i 49) 32 :else 8)
                                                                                                                                                                  (cond (= q :pressure-io-some-us) 100 (str/starts-with? r "core-") 100000 :else 1000000)))))
                        :reason (when (= i 59) :counter-reset) :method :declared}))]
    (assoc (branch) :resources resources :measurements samples)))
(m/=> signals [:=> [:cat] c/Graph])

(defn incomplete
  "Retain cancellation, cache, deselection and blocked decisions without invented execution bars."
  []
  (let [decision (fn [node outcome reason at]
                   (-> node (dissoc :attempt :interval)
                       (assoc :record :decision :outcome outcome :at-ns at
                              :reason {:code reason :detail "Synthetic decision evidence"})))
        nodes (:nodes (branch))]
    (-> (branch)
        (assoc-in [:run :complete?] false)
        (assoc-in [:run :end-ns] nil)
        (assoc :edges [] :nodes [(-> (first nodes) (assoc :outcome :running) (assoc-in [:interval :end-ns] nil))
                                 (decision (nth nodes 1) :cached :cache-hit "0")
                                 (-> (nth nodes 2) (assoc :outcome :cancelled) (assoc-in [:interval :end-ns] "160000000"))
                                 (decision (nth nodes 3) :blocked :prerequisite-failed "100000000")
                                 (decision (nth nodes 4) :deselected :unchanged "160000000")]))))
(m/=> incomplete [:=> [:cat] c/Graph])

(defn leaf
  "A valid single gate must remain visible without a synthetic container."
  []
  (-> (branch) (assoc :edges [] :nodes [(assoc (last (:nodes (branch))) :parent nil)])
      (assoc-in [:run :expected-keys] ["D-package"])))
(m/=> leaf [:=> [:cat] c/Graph])

(defn open-signals
  "Resource evidence continues after the last task event in an incomplete capture."
  []
  (merge (incomplete) (select-keys (signals) [:resources :measurements])))
(m/=> open-signals [:=> [:cat] c/Graph])

(defn -main
  "Write self-contained public fixtures to a caller-owned new output directory."
  [& [output]]
  (let [bundle (:javascript (asset/load!))]
    (doseq [[fixture-name graph] [["branch" (branch)] ["dense" (dense)] ["signals" (signals)] ["incomplete" (incomplete)]
                                  ["leaf" (leaf)] ["open-signals" (open-signals)]]]
      (let [dir (io/file output fixture-name)] (.mkdirs dir)
           (spit (io/file dir "graph.edn") (canonical/encode graph 134217728))
           (spit (io/file dir "index.html") (report/render graph bundle))))
    (let [file (io/file output "malformed" "index.html")]
      (io/make-parents file)
      (spit file (str/replace (report/render (branch) bundle)
                              #"(?s)(<script id=\"gate-data\" type=\"application/edn\">).*?(</script>)" "$1{}$2"))))
  nil)
(m/=> -main [:=> [:cat [:? :string]] :nil])
