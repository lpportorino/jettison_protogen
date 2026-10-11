(ns press.doc.negatives
  "The description corpus both halves are held to. `cases`: one invalid
   description per refusal class of `desc.py`, each a single change to
   `press.doc.fixture/diagram` — or, for a chart ENVELOPE case (named
   `chart-*`), to `chart-base` — with the rule `press.doc.desc/problems` names;
   `positives`: descriptions both must accept. `write-corpus!` writes them
   as the tracked fixture directories `tools/press/fixtures/desc_negative/`
   and `desc_positive/`; the JVM test compares those directories with what
   this namespace generates (drift is red) and judges every file, and
   `press selftest` judges the same files on the browser side.
   `clojure -M:corpus` (from `tools/press`) rewrites both directories."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [press.config :as config]
            [press.doc.figure :as fig]
            [press.doc.fixture :as fx]))

(def ^:private float-sentinel 77777)

(def ^:private surrogate-sentinel "SURROGATE-SENTINEL")

(def chart-base
  "The smallest chart description both halves accept (`press chart` validates
   its `data`; the JVM half checks only the envelope)."
  {:id "fig1" :kind :chart :chart :bar
   :data {:title "t" :rows [{:label "a" :tone "NAVY" :value 1}]}})

(defn base-of
  "The description a case mutates: `chart-base` for a `:chart` case, else
   `press.doc.fixture/diagram`."
  [flags]
  (if (some #{:chart} flags) chart-base fx/diagram))

(defn- add-node
  "`d` with an extra node."
  [d n]
  (update d :nodes conj n))

(def cases
  "`[name rule mutation & flags]` — every refusal class, one change each,
   with the rule `press.doc.desc/problems` names for it."
  (let [nodes-at (fn [i k v] #(assoc-in % [:nodes i k] v))
        group-at (fn [k v] #(assoc-in % [:groups 0 k] v))
        bus-at (fn [k v] #(assoc-in % [:buses 0 k] v))
        edge-at (fn [k v] #(assoc-in % [:edges 1 k] v))
        constraint (fn [c] #(update % :constraints conj c))
        g2 {:id "g2" :look "white" :children ["c1"]}]
    [["unknown-top-key" :desc/unknown-key #(assoc % :colour "red")]
     ["missing-id" :desc/missing-key #(dissoc % :id)]
     ["kind-bad" :desc/bad-value #(assoc % :kind :pie)]
     ["nodes-empty" :desc/bad-value #(assoc % :nodes [])]
     ["unknown-node-key" :desc/unknown-key (nodes-at 0 :colour "red")]
     ["node-missing-look" :desc/missing-key #(update-in % [:nodes 0] dissoc :look)]
     ["node-unknown-look" :desc/bad-value (nodes-at 0 :look "neon")]
     ["node-rows-and-cells" :desc/rows-xor-cells (nodes-at 0 :cells [["n" "x"]])]
     ["node-no-rows-no-cells" :desc/rows-xor-cells #(update-in % [:nodes 1] dissoc :rows)]
     ["row-empty-text" :desc/bad-value (nodes-at 1 :rows [["n" ""]])]
     ["row-unknown-style" :desc/bad-value (nodes-at 1 :rows [["zz" "x"]])]
     ["row-not-a-pair" :desc/bad-value (nodes-at 1 :rows [["n" "x" "y"]])]
     ["cells-without-column-set" :desc/column-set #(update-in % [:nodes 2] dissoc :column-set)]
     ["column-set-with-rows" :desc/column-set (nodes-at 1 :column-set "steps")]
     ["column-set-width-mismatch" :desc/column-set (nodes-at 3 :cells [["bt" "Step 2"]])]
     ["pad-negative" :desc/bad-value (nodes-at 0 :pad -1)]
     ["pad-float" :desc/float (nodes-at 0 :pad float-sentinel) :float]
     ["int-out-of-range" :desc/int-range (nodes-at 0 :pad 100001)]
     ["lone-surrogate" :desc/surrogate (nodes-at 1 :rows [["n" surrogate-sentinel]]) :surrogate]
     ["align-bad" :desc/bad-value (nodes-at 0 :align "right")]
     ["node-id-bad-chars" :desc/bad-value #(add-node % {:id "x y" :look "node" :rows [["n" "z"]]})]
     ["duplicate-id" :desc/duplicate-id #(add-node % {:id "hub" :look "node" :rows [["n" "z"]]})]
     ["group-missing-look" :desc/missing-key #(update-in % [:groups 0] dissoc :look)]
     ["group-children-empty" :desc/bad-value (group-at :children [])]
     ["group-child-unknown" :desc/group-child (group-at :children ["ghost"])]
     ["group-contains-itself" :desc/group-self (group-at :children ["host"])]
     ["child-in-two-groups" :desc/group-parent #(update % :groups conj {:id "g2" :look "white" :children ["hub"]})]
     ["group-cycle" :desc/group-cycle #(update % :groups into [{:id "ga" :look "white" :children ["gb"]}
                                                               {:id "gb" :look "white" :children ["ga"]}])]
     ["group-layout-bad" :desc/bad-value (group-at :layout "grid")]
     ["collapsible-not-bool" :desc/bad-value (group-at :collapsible "yes")]
     ["group-title-bad-row" :desc/bad-value (group-at :title [["zz" "x"]])]
     ["bus-orient-bad" :desc/bad-value (bus-at :orient "d")]
     ["bus-mode-bundle" :desc/bad-value (bus-at :mode "bundle")]
     ["bus-look-unknown" :desc/bad-value (bus-at :look "neon")]
     ["bus-span-bad" :desc/bad-value (bus-at :span "page")]
     ["bus-taps-empty" :desc/bad-value (bus-at :taps [])]
     ["tap-names-a-bus" :desc/tap #(update % :buses conj {:id "bus2" :orient "v" :taps ["bus"]})]
     ["tap-unknown-key" :desc/unknown-key (bus-at :taps ["hub" {:node "node" :colour "x"}])]
     ["tap-twice" :desc/tap (bus-at :taps ["hub" "hub"])]
     ["edge-missing-kind" :desc/missing-key #(update-in % [:edges 1] dissoc :kind)]
     ["edge-dash-key" :desc/unknown-key (edge-at :dash true)]
     ["edge-end-is-a-bus" :desc/edge-end (edge-at :to "bus")]
     ["self-edge" :desc/self-edge (edge-at :to "c1")]
     ["edge-kind-bad" :desc/bad-value (edge-at :kind "line")]
     ["edge-side-bad" :desc/bad-value (edge-at :from-side "x")]
     ["edge-look-unknown" :desc/bad-value (edge-at :look "neon")]
     ["edge-arrow-bad" :desc/bad-value (edge-at :arrow "start")]
     ["duplicate-edge-ids" :desc/edge-id #(update % :edges conj {:id "e1" :from "c2" :to "c1" :kind "arrow"})]
     ["default-edge-id-collision" :desc/edge-id #(update % :edges conj {:from "c1" :to "c2" :kind "arrow"})]
     ["edge-id-bad-chars" :desc/bad-value (edge-at :id "a>b")]
     ["unknown-constraint" :desc/constraint-name (constraint ["glue" "hub"])]
     ["constraint-arity" :desc/constraint-arity (constraint ["order-x" ["hub" "node"]])]
     ["ids-too-few" :desc/constraint-arg (constraint ["same-x" ["hub"]])]
     ["ids-duplicate" :desc/constraint-arg (constraint ["same-x" ["hub" "hub"]])]
     ["constraint-unknown-id" :desc/constraint-arg (constraint ["span-x" "ghost"])]
     ["follow-third-not-a-bus" :desc/constraint-arg (constraint ["follow" "node" "hub" "host" 2])]
     ["hang-side-bad" :desc/constraint-arg (constraint ["hang" "node" "bus" 3 3 "up"])]
     ["order-y-min-over-max" :desc/gap-order (constraint ["order-y" ["c1" "c2"] 6 2])]
     ["hang-min-over-max" :desc/gap-order (constraint ["hang" "node" "bus" 5 3 "free"])]
     ["near-unknown-edge" :desc/constraint-arg (constraint ["near" "node" ["e9"] "e" 4])]
     ["constraint-negative-int" :desc/constraint-arg (constraint ["beside" "c1" "c2" -1])]
     ["constraint-not-a-list" :desc/bad-value (constraint "order-x")]
     ["soft-unknown-key" :desc/unknown-key #(assoc % :soft {:width 3})]
     ["soft-not-an-int" :desc/bad-value #(assoc % :soft {:height "10"})]
     ["view-unknown-key" :desc/unknown-key #(assoc % :view {:zoom 2})]
     ["view-collapse-not-collapsible" :desc/view-collapse #(-> % (update :groups conj g2) (assoc :view {:collapse ["g2"] :refs {"g2" "Figure 2"}}))]
     ["view-collapse-without-ref" :desc/view-collapse #(assoc % :view {:collapse ["host"]})]
     ["view-collapse-not-a-list" :desc/bad-value #(assoc % :view {:collapse "host" :refs {"host" "Figure 2"}})]
     ["view-detail-without-ref" :desc/view-detail #(assoc % :view {:detail "host"})]
     ["view-refs-not-a-group" :desc/view-refs #(assoc % :view {:refs {"hub" "Figure 2"}})]
     ["view-depth-zero" :desc/bad-value #(assoc % :view {:depth 0})]
     ["view-depth-needs-a-ref" :desc/view-collapse #(-> % (update :groups conj {:id "g2" :look "white" :children ["host"]})
                                                        (assoc :view {:depth 1}))]
     ["nl-node-id" :desc/bad-value #(add-node % {:id "zz\n" :look "node" :rows [["b" "x"]]})]
     ["nl-edge-id" :desc/bad-value (edge-at :id "e2\n")]
     ["detail-marker-collision" :desc/duplicate-id
      #(-> % (add-node {:id "ref-node" :look "node" :rows [["b" "x"]]})
           (update-in [:groups 0 :children] conj "ref-node")
           (assoc :view {:detail "host" :refs {"host" "Figure 2"}}))]
     ["detail-note-collision" :desc/duplicate-id
      #(-> % (add-node {:id "host-part-of" :look "node" :rows [["b" "x"]]})
           (update-in [:groups 0 :children] conj "host-part-of")
           (assoc :view {:detail "host" :refs {"host" "Figure 2"}}))]
     ["collapse-twice" :desc/view-collapse-twice #(assoc % :view {:collapse ["host" "host"] :refs {"host" "Figure 2"}})]
     ["chart-type-unknown" :desc/bad-value #(assoc % :chart :pie) :chart]
     ["chart-data-not-object" :desc/bad-value #(assoc % :data ["x"]) :chart]
     ["chart-unknown-key" :desc/unknown-key #(assoc % :title "t") :chart]
     ["chart-missing-data" :desc/missing-key #(dissoc % :data) :chart]
     ["chart-int-range" :desc/int-range #(assoc-in % [:data :rows 0 :value] 100001) :chart]
     ["chart-float" :desc/float #(assoc-in % [:data :rows 0 :value] float-sentinel) :chart :float]]))

(defn- json-of
  "Description `d` as the JSON text `press` reads. The float and the
   lone-surrogate cases have no canonical form `press.doc` can write, so a
   sentinel is replaced by `1.5` / the escape `\\ud800` after writing."
  [d flags]
  (let [j (fig/canonical-json d)]
    (cond-> j
      (some #{:float} flags) (str/replace (str float-sentinel) "1.5")
      (some #{:surrogate} flags) (str/replace surrogate-sentinel "\\ud800"))))

(defn edn-text
  "Case `[nm rule f & flags]` as the `figs/fig1.edn` text an author would
   write: the float case carries `1.5`, the surrogate case the EDN escape
   `\\uD800` (a lone surrogate cannot be written to a UTF-8 file as itself)."
  [[_ _ f & flags]]
  (let [t (pr-str (f (base-of flags)))]
    (cond-> t
      (some #{:float} flags) (str/replace (str float-sentinel) "1.5")
      (some #{:surrogate} flags) (str/replace surrogate-sentinel "\\uD800"))))

(def positives
  "`[name description]` — descriptions both halves ACCEPT that are close to a
   refusal class (the detail-plus-depth view counts depth on the detailed
   tree, as `desc.py` does)."
  [["base" fx/diagram]
   ["detail-depth"
    (-> fx/diagram
        (update :nodes conj {:id "x1" :look "node" :rows [["b" "x"]]})
        (update :groups into [{:id "A" :look "white" :children ["B"]}
                              {:id "B" :look "white" :collapsible true :children ["C"]}
                              {:id "C" :look "white" :collapsible true :children ["x1"]}])
        (assoc :view {:depth 2 :detail "B" :refs {"B" "Figure 2"}}))]])

(def negative-dir
  "Where the negative corpus lives, relative to the press library's own
   repository root (`press.config/default-root`)."
  "tools/press/fixtures/desc_negative")

(def positive-dir
  "Where the positive corpus lives, relative to the same root."
  "tools/press/fixtures/desc_positive")

(defn- manifest
  "The corpus manifest: per file, its case name and (for a negative) the
   rule `press.doc.desc/problems` refuses it with."
  [doc entries]
  (fig/canonical-json {"_doc" doc "cases" (vec (sort-by #(get % "file") entries))}))

(defn negative-corpus
  "File name → exact bytes of the negative corpus: one canonical-JSON
   description per case, named `<case>.fig.json` with `id` = the case (so
   desc.py's file-stem rule never decides a case), plus `manifest.json`."
  []
  (into (sorted-map)
        (cons ["manifest.json"
               (manifest (str "Generated by press.doc.negatives/write-corpus! — do not edit. One description per "
                              "refusal class of tools/press/press/fig/desc.py, plus the chart ENVELOPE "
                              "refusals of tools/press/press/chart/desc.py (files named chart-*); both halves "
                              "must refuse every file. `rule` is the rule press.doc.desc/problems names.")
                         (for [[nm rule] cases] {"file" (str nm ".fig.json") "case" nm "rule" (subs (str rule) 1)}))]
              (for [[nm _ f & flags] cases]
                [(str nm ".fig.json") (json-of (f (assoc (base-of flags) :id nm)) flags)]))))

(defn positive-corpus
  "File name → exact bytes of the positive corpus (both halves accept every
   file), plus `manifest.json`."
  []
  (into (sorted-map)
        (cons ["manifest.json"
               (manifest (str "Generated by press.doc.negatives/write-corpus! — do not edit. Descriptions both "
                              "halves must ACCEPT.")
                         (for [[nm] positives] {"file" (str nm ".fig.json") "case" nm}))]
              (for [[nm d] positives]
                [(str nm ".fig.json") (fig/canonical-json (assoc d :id nm))]))))

(defn write-corpus!
  "Write both corpora under the root directory `root` (replacing what is
   there). Returns the number of files written."
  [root]
  (reduce + (for [[dir corpus] [[negative-dir (negative-corpus)] [positive-dir (positive-corpus)]]]
              (let [d (io/file root dir)]
                (.mkdirs d)
                (doseq [^java.io.File f (.listFiles d)] (.delete f))
                (doseq [[nm text] corpus] (spit (io/file d nm) text))
                (count corpus)))))

(defn -main
  "Write both corpora under the optional explicit destination root. With no
   argument, use the configured repository root for deliberate regeneration."
  [& args]
  (when (> (count args) 1)
    (throw (ex-info "expected at most one destination root" {:args args})))
  (let [root (or (first args) (config/default-root))]
    (println (str "wrote " (write-corpus! root) " corpus files under " root "/tools/press/fixtures"))))
