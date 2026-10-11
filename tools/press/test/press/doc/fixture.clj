(ns press.doc.fixture
  "Throwaway document directories for the press.doc tests. A document lives
   INSIDE the configured root (host commands are root-relative), so each
   one is made under `<root>/.press/doc-test/<random>/docs/<name>` with a
   unique name, and `cleanup` removes it and its build directory
   `<build-dir>/<name>` after each test — and then `.press/doc-test`, the
   build directory and `.press` themselves when that leaves them empty."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [press.config :as config]
            [press.doc :as doc]
            [press.doc.figure :as fig])
  (:import [java.io File]
           [java.util UUID]))

(def ^:private made (atom []))

(defn refusal
  "The `:doc/refusal` code `(f)` throws — `:malli/boundary` when the
   instrumented boundary refused the call first — or `:no-throw`."
  [f]
  (try (f) :no-throw
       (catch clojure.lang.ExceptionInfo e
         (or (:doc/refusal (ex-data e))
             (when (str/starts-with? (ex-message e) "Malli ") :malli/boundary)))))

(defn- delete-tree!
  "Remove `f` and everything under it."
  [^File f]
  (when (.exists f)
    (doseq [^File x (reverse (file-seq f))] (.delete x))))

(def scratch
  "Where the tests make their documents, root-relative."
  ".press/doc-test")

(defn build-root
  "The configured build directory, root-relative as configured."
  []
  (config/build-dir))

(defn- prune-empty!
  "Remove the scratch parents the tests created, each only when empty
   (`File.delete` refuses a directory that still holds something)."
  []
  (doseq [p [scratch (build-root) ".press"]]
    (.delete (io/file (config/from-root p)))))

(defn cleanup
  "A clojure.test `:each` fixture: run the test, then remove every
   directory it made."
  [t]
  (try (t)
       (finally
         (doseq [f @made] (delete-tree! f))
         (reset! made [])
         (prune-empty!))))

(defn uid
  "A short random token."
  []
  (subs (str/replace (str (UUID/randomUUID)) "-" "") 0 10))

(defn scratch-dir!
  "A fresh directory under `<root>/.press/doc-test/`, removed by `cleanup`."
  []
  (let [d (io/file (config/from-root scratch) (uid))]
    (.mkdirs d)
    (swap! made conj d)
    d))

(def diagram
  "A small description `desc.py` accepts, touching every element kind."
  {:id "fig1" :kind :diagram
   :nodes [{:id "hub" :look "hub" :rows [["t" "hub"] ["n" "lists workers"]] :pad 5 :align "left"}
           {:id "node" :look "node" :rows [["b" "worker 3"]]}
           {:id "c1" :look "node" :cells [["bt" "Step 1"] ["m" "x"]] :column-set "steps"}
           {:id "c2" :look "node" :cells [["bt" "Step 2"] ["m" "y"]] :column-set "steps"}]
   :groups [{:id "host" :look "host" :title [["h" "Host"]] :children ["hub"] :layout "stack"
             :collapsible true :align "center"}]
   :buses [{:id "bus" :orient "h" :mode "spine" :label [["h" "worker bus"]] :look "bus" :span "taps"
            :taps ["hub" {:node "node" :look "tap"}]}]
   :edges [{:id "e1" :from "hub" :to "node" :kind "arrow" :from-side "s" :to-side "n" :look "dashed" :arrow "end"}
           {:from "c1" :to "c2" :kind "skip"}]
   :constraints [["order-x" ["hub" "node"] 1] ["hang" "node" "bus" 3 3 "free"] ["within-y" "hub" "host"]
                 ["order-y" ["c1" "c2"] 2 6] ["near" "node" ["e1"] "e" 4]]
   :soft {:height 10 :edges 1}})

(def svg
  "A tiny SVG in the figure renderer's exact vocabulary."
  (str "<svg viewBox=\"0 0 476 40\" xmlns=\"http://www.w3.org/2000/svg\" class=\"svgtxt\" "
       "style=\"font-family:Inter,'Liberation Sans',Arial,sans-serif;font-feature-settings:'calt' 0\">\n"
       "<defs>\n<marker id=\"arr\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"6\" markerHeight=\"6\" "
       "orient=\"auto-start-reverse\"><path d=\"M0 0L10 5L0 10z\" fill=\"#1f3a5f\"/></marker>\n</defs>\n"
       "<rect class=\"node\" data-id=\"hub\" x=\"0.5\" y=\"0.5\" width=\"99\" height=\"29\" rx=\"4\" fill=\"#d8f0ee\" "
       "stroke=\"#1a7f8e\" stroke-width=\"1\"/>\n"
       "<text x=\"6\" y=\"14\" font-size=\"8.5\" font-weight=\"700\" fill=\"#1a7f8e\" style=\"white-space:pre\">hub &amp; co</text>\n"
       "<path class=\"edge\" data-id=\"e1\" data-net=\"e1\" d=\"M100 15 L200 15\" fill=\"none\" stroke=\"#1f3a5f\" "
       "stroke-width=\"1.2\" marker-end=\"url(#arr)\"/>\n</svg>\n"))

(defn write-fig!
  "Write figure `desc` into `doc-dir`: its description, and unless told
   otherwise its SVG and a pick record matching both."
  ([doc-dir desc] (write-fig! doc-dir desc {}))
  ([doc-dir desc {:keys [svg-text pick? svg?] :or {svg-text svg pick? true svg? true}}]
   (let [id (:id desc)]
     (io/make-parents (fig/fig-file doc-dir id ".edn"))
     (spit (fig/fig-file doc-dir id ".edn") (pr-str desc))
     (when svg? (spit (fig/fig-file doc-dir id ".svg") svg-text))
     (when pick?
       (spit (fig/fig-file doc-dir id ".pick.json")
             (json/generate-string {"id" id "problem_hash" (fig/sha256 (fig/canonical-json desc))
                                    "svg_sha256" (fig/sha256 svg-text) "solver" "test" "tile" 1}))))))

(defn with-figure
  "The example document with figure `fig1` placed in its first section."
  []
  (update-in (doc/example) [:body 0 :sections 0 :blocks] conj
             [:doc/p "The figure shows the hub and one node."]
             [:doc/fig "fig1" [[:b "The hub and a node."] " What you see: one arrow."]]))

(defn doc-dir!
  "A document directory holding `d` as `doc.edn` (and `fig1` when `fig?`),
   inside the configured root, named `nm` (default: a unique `d<random>`).
   Returns the directory's absolute path."
  ([d] (doc-dir! d false nil))
  ([d fig?] (doc-dir! d fig? nil))
  ([d fig? nm]
   (let [nm (or nm (str "d" (uid)))
         dir (io/file (scratch-dir!) "docs" nm)]
     (swap! made conj (io/file (config/from-root (build-root)) nm))
     (.mkdirs dir)
     (spit (io/file dir "doc.edn") (pr-str d))
     (when fig? (write-fig! (str dir) diagram))
     (str dir))))

(defn rel
  "`p` relative to the configured root."
  [p]
  (str (.relativize (.toPath (io/file (config/from-root "."))) (.toPath (.getCanonicalFile (io/file p))))))

(defn name-of
  "The document name of directory `d`."
  [d]
  (.getName (io/file d)))

(defn rules-of
  "The set of violation rules `check` reports for `d` (with `fig1` written)."
  [d]
  (set (map :rule (:violations (doc/check (doc-dir! d true))))))
