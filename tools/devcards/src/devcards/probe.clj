(ns devcards.probe
  "Generic interaction-probe helpers over a devcards host — the public
   plug-in seam for E2E-probing ANY rendered screen, promoted from the
   composition lane's private helpers so consumers author probe SPECS, not
   probe infrastructure. Pairs with `devcards.pointer` (tap!/drag!/events —
   the input+capture half); this ns owns host lifecycle, dump-tree
   navigation, pixel sampling, and the findings shape the corpus verdict
   folds ({:gate :interaction ...}; empty = green).

   HOW-public / WHAT-private: these helpers know nothing about any corpus —
   a consumer boots its own host over its own wasm/screens and asserts its
   own contracts through them."
  (:require [clojure.data.json :as json]
            [devcards.host :as host]))

(set! *warn-on-reflection* true)

(defn events
  "Decode every captured host_event envelope
   ({\"v\":1,\"tag\":...,\"origin\":...,\"event\":...,\"seq\":...,\"value\":...}).
   The capture-READING half of the probe surface; the input-INJECTION half
   (tap!/drag!, which needs the generated proto classes) is
   `devcards.pointer` — the split keeps this ns loadable without compiled
   bindings."
  [{:keys [captured] :as _host}]
  (mapv #(json/read-str (String. ^bytes % "UTF-8") :key-fn keyword) (:events @captured)))

(defn events-tagged
  "The captured envelopes whose :tag equals `tag`, in emission order."
  [h tag]
  (filterv #(= tag (:tag %)) (events h)))

(defn clear-events!
  "Reset the captured host_event lane between probe assertions."
  [{:keys [captured] :as _host}]
  (swap! captured assoc :events []))

(defn with-host
  "Run `f` over a fresh host from `boot!` (a no-arg fn returning a started
   devcards.host); always closes it. Fresh-host-per-probe is the hermetic
   builder law: a reused host carries widget state (a repeat tap at an
   unchanged value fires no VALUE_CHANGED and fakes a miss)."
  [boot! f]
  (let [h (boot!)] (try (f h) (finally (host/close! h)))))

(defn render-drained!
  "Render `pb` on host `h` under `opts` ({:bp :dark}) and drain any
   render-time host events, so the probe reads interaction envelopes only.
   Returns the framebuffer."
  ^bytes [h ^bytes pb {:keys [bp dark] :or {bp 0 dark 1}}]
  (let [fb (host/render-card! h {:pb pb :bp bp :dark dark})]
    (clear-events! h)
    fb))

(defn dump-tree
  "The host's current dump tree, parsed with keyword keys."
  [h]
  (json/read-str (host/dump-tree! h) :key-fn keyword))

(defn node-seq
  "Depth-first preorder seq of every node in dump tree `tree`."
  [tree]
  (tree-seq #(seq (:children %)) :children tree))

(defn find-type
  "Depth-first preorder nodes of dump tree `tree` whose :type is `t` (the
   same order the wasmtime harness walks, so indices agree cross-engine)."
  [tree t]
  (filterv #(= t (:type %)) (node-seq tree)))

(defn find-uid
  "The first node in dump tree `tree` carrying codegen uid `uid`, or nil.
   Uids are the codegen's node identity — the stable per-control address a
   consumer probe taps by."
  [tree uid]
  (first (filter #(= uid (:uid %)) (node-seq tree))))

(defn center
  "The px center point of a dump node's inclusive :coords rect."
  [{:keys [coords]}]
  (let [[x1 y1 x2 y2] coords] [(quot (+ x1 x2) 2) (quot (+ y1 y2) 2)]))

(defn finding
  "A probe finding map when `expected` != `actual`, else nil — the shape the
   corpus verdict folds (empty seq = green)."
  [card check expected actual]
  (when (not= expected actual)
    {:gate :interaction :card (str card) :check check :expected expected :actual actual}))

(defn hex-rgb
  "\"#rrggbb\" -> [r g b]."
  [^String hex]
  (mapv (fn [^long i] (Integer/parseInt (subs hex i (+ i 2)) 16)) [1 3 5]))

(defn px-at
  "[r g b] at canvas px [x y] in raw RGBA framebuffer bytes (`canvas-w` px
   wide) — sample a rendered color at a point."
  [^bytes fb ^long canvas-w [x y]]
  (let [i (* (+ (* (long y) canvas-w) (long x)) 4)]
    (mapv (fn [^long c] (bit-and (aget fb (+ i c)) 0xFF)) [0 1 2])))
