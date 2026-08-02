(ns live-probe
  "MEASURES the scratchcard render path end to end against the real wasm:
  build bytes -> render -> dump -> stats -> quality lanes.

  Run it (GraalVM required — a stock JDK is refused by the host's own guard):

      tools/uber.sh 'cd tools/scratchcard && clojure -M:live-probe'

  A HAND-RUN PROBE, WIRED INTO NOTHING. It prints what it measured; it
  asserts nothing and gates nothing. The standing automated form of this
  proof is the Tier-2 integration lane.

  This banner says WHAT IT MEASURES and HOW TO RUN IT — never what it found.
  A probe whose prose states a result has moved recall inside the repository."
  (:require
   [clojure.data.json :as json]
   [clojure.string :as str]
   [devcards.fixtures :as fx]
   [devcards.host :as host]
   [scratchcard.lanes :as lanes]
   [scratchcard.matrix :as matrix]
   [scratchcard.png :as png]
   [scratchcard.provenance :as prov]
   [scratchcard.stats :as stats]))

(def ^:private workspace
  (or (System/getenv "UBER_WORKSPACE") "/workspace"))

(defn- button
  "A button with its child label.

  The label is not decoration: `devcards.fixtures` enforces a BUILDER LAW that
  a WIDGET_BUTTON carries a child WIDGET_LABEL, and refuses the screen
  otherwise. Measured by writing this probe without one."
  [x y text]
  {:type :WIDGET_BUTTON :x x :y y :props {:w 120 :h 44}
   :children [{:type :WIDGET_LABEL :text text}]})

(defn- probe-node
  "A small authored screen with two deliberately OVERLAPPING buttons, so the
  pointer-hazard lane has something to find and the run is not vacuously
  clean. A probe that can only ever report success measures nothing."
  []
  {:type :WIDGET_OBJ
   :props {:w 400 :h 200}
   :children [{:type :WIDGET_LABEL :text "scratchcard live probe" :x 20 :y 10}
              (button 20 60 "one")
              (button 100 80 "two")]})

(defn- render-cell!
  [pb {:keys [family dark w h bp] :as cell}]
  (let [h* (host/start! {:wasm (str workspace "/renderer/output/controls.wasm")
                         :assets (str workspace "/renderer/assets")
                         :w w :h h})]
    (try
      (when (pos? (long family)) (host/set-theme-family! h* family))
      (let [t0 (System/nanoTime)
            fb (host/render-card! h* {:pb pb :bp bp :dark dark})
            render-ms (/ (- (System/nanoTime) t0) 1e6)
            dump (host/dump-tree! h*)
            tree (json/read-str dump :key-fn keyword)
            label (matrix/cell-label cell)
            st (stats/collect {:host h* :framebuffer fb :pb-bytes (alength ^bytes pb)
                               :dump-bytes (count dump)
                               :timings {:render-ms render-ms} :w w :h h})
            judged (lanes/judge {:card-id "live-probe" :tree tree
                                 :emissions (some-> (:captured h*) deref)
                                 :family family :cell label})]
        {:cell label
         :ms render-ms
         :abi (:abi h*)
         :fb-sha (subs (get-in st [:framebuffer :sha256]) 0 12)
         :opaque (get-in st [:framebuffer :opaque-pixels])
         :dirty (:dirty-rect st)
         :dump-bytes (count dump)
         :png-bytes (alength (png/encode fb w h))
         :findings (mapv :invariant (:live judged))})
      (finally (host/close! h*)))))

(defn -main
  [& _]
  (let [pb (fx/build-authored-card {:w 400 :h 200} {:id "live-probe" :node (probe-node)})
        cells (matrix/expand {:resolutions [{:w 400 :h 200} {:w 800 :h 480}]})
        t0 (System/nanoTime)
        results (mapv #(render-cell! pb %) cells)
        total-ms (/ (- (System/nanoTime) t0) 1e6)]
    (println "pb bytes:" (alength ^bytes pb))
    (println "protocol:" (:protocol (prov/collect {:repo-root workspace
                                                   :image-tag nil :runtime {}})))
    (doseq [r results]
      (println (format "  %-28s %6.1fms fb=%s opaque=%-8d dump=%-6d png=%-7d %s"
                       (:cell r) (:ms r) (:fb-sha r) (:opaque r)
                       (:dump-bytes r) (:png-bytes r) (pr-str (:findings r)))))
    (println (format "cells: %d  wall: %.1fms  mean: %.1fms"
                     (count results) total-ms (/ total-ms (count results))))
    (println "vanilla==stock per (mode,canvas):"
             (->> results
                  (group-by #(re-find #"(light|dark)-\d+x\d+" (:cell %)))
                  (map (fn [[k rs]]
                         [k (= (:fb-sha (first (filter #(str/starts-with? (:cell %) "vanilla") rs)))
                               (:fb-sha (first (filter #(str/starts-with? (:cell %) "stock") rs))))]))
                  (into {})))
    (shutdown-agents)))
