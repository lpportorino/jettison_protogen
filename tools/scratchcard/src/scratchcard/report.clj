(ns scratchcard.report
  "The two things a reader consults after a run: `findings.edn` and `report.md`.

  THEY HAVE DIFFERENT CONSUMERS AND THAT SPLIT IS DELIBERATE. `findings.edn`
  is the full vector, machine-readable, for a diff or a script.
  `report.md` is a DIGEST for whatever reads the PNGs beside it — never a
  second source of truth, and never larger than it needs to be.

  FINDINGS ARE WRITTEN BEFORE THE VERDICT IS COMPUTED. `devcards.core` does
  the same, deliberately: if the summarising step throws, the evidence is
  already on disk. A run that crashes while deciding what to say about itself
  must not also lose what it found.

  THE SUMMARY GOES FIRST, and that is an ergonomics decision with a reason. The
  reader is a model with a bounded context that will act on the first thing it
  understands. A report that opens with forty rows of per-cell tables and
  buries the verdict at the bottom gets skimmed, and the skim is what the
  reader acts on.

  IT ALWAYS STATES WHAT WAS NOT JUDGED. A lane that passes over what it could
  not classify reports \"clean\" and \"I could not look\" as the same empty
  vector. The declined producers are named in every report, so silence is
  never mistakable for coverage.

  IT NEVER IMPLIES A CONDITION IT DID NOT IMPOSE. No readability, contrast,
  sunlight or night-vision claim appears here: those are properties of a PANEL
  and an OPERATOR, are governed upstream, and this repo has never measured
  them. A pass message that implied one would be exactly the over-claim
  `docs/UI-QUALITY-CONTRACTS.md` §0 forbids."
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.string :as str])
  (:import
   (java.io File)))

(def findings-name "findings.edn")
(def report-name "report.md")

(defn write-findings!
  "Persist the FULL findings vector. Returns the file."
  ^File [^File run-dir cell-results]
  (let [f (io/file run-dir findings-name)]
    (io/make-parents f)
    (with-open [w (io/writer f)]
      (pp/pprint (into [] (mapcat :live) cell-results) w))
    f))

(defn- md-table
  [headers rows]
  (when (seq rows)
    (str/join "\n"
              (concat [(str "| " (str/join " | " headers) " |")
                       (str "|" (str/join "|" (repeat (count headers) "---")) "|")]
                      (for [row rows]
                        (str "| " (str/join " | " (map #(str/replace (str %) "|" "\\|") row)) " |"))))))

(defn- verdict-line
  [{:keys [run findings]} cell-count failed-count]
  (let [{:keys [status]} run]
    (cond
      (= :error status)
      (str "**FAILED — " (get-in run [:error :code]) "**: "
           (get-in run [:error :message]))

      (pos? (long failed-count))
      (str "**" (- (long cell-count) (long failed-count)) "/" cell-count
           " cells rendered; " failed-count " FAILED**")

      (:clean? findings)
      (str "**CLEAN — " cell-count " cells rendered, no findings**")

      :else
      ;; NOT "OK". Every cell rendering is not the same claim as the screen
      ;; being sound, and a verdict that conflates them trains a reader to
      ;; skip the findings table underneath it.
      (str "**RENDERED, WITH FINDINGS — " cell-count " cells, "
           (:count findings) " finding(s)"
           (when (pos? (long (:unjudged findings 0)))
             (str ", " (:unjudged findings) " of which mean COULD-NOT-JUDGE"))
           "**"))))

(defn report-md
  "The digest. `manifest` is the validated run manifest."
  ^String [{:keys [run protogen wasm lanes renders findings input] :as manifest}]
  (let [failed (count (remove #(= :ok (:status %)) renders))
        finding-rows (for [{:keys [cell invariant node detail]} (:live-sample findings)]
                       [cell invariant (or node "") (or detail "")])
        render-rows (for [r renders]
                      [(:cell r) (:disp-tier r) (:status r)
                       (or (some-> (:fb-sha256 r) (subs 0 12)) "-")
                       (or (get-in r [:stats :timings-ms :render-ms]) "-")])]
    (str/join
     "\n\n"
     (remove nil?
             [(str "# scratchcard " (:card run) " — run " (:id run))
              (verdict-line manifest (count renders) failed)

              (str "- input `" (:path input) "` sha `"
                   (some-> (:sha256 input) (subs 0 12)) "`\n"
                   "- protogen `" (:short-sha protogen) "`"
                   (when (:dirty? protogen) " **(dirty tree — this sha does not identify what rendered)**") "\n"
                   "- wasm sha `" (some-> (:sha256 wasm) (subs 0 12))
                   "` abi " (:abi wasm) "\n"
                   "- elapsed " (:elapsed-ms run) " ms")

              (when (seq finding-rows)
                (str "## Findings\n\n" (md-table ["cell" "invariant" "node" "detail"] finding-rows)))

              (str "## Renders\n\n"
                   (md-table ["cell" "tier" "status" "fb sha" "ms"] render-rows))

              ;; ALWAYS present, even on a clean run.
              (str "## What was NOT judged\n\n"
                   "- producers declined for this surface: "
                   (str/join ", " (map name (:declined lanes)))
                   " — see the lane docstrings for why each cannot apply here.\n"
                   "- this report judges GEOMETRY, DOM invariants and emissions.\n"
                   "- it makes NO claim about readability, contrast, or legibility\n"
                   "  under any lighting or panel condition: those are properties\n"
                   "  of a panel and an operator, measured at a bench, not here.")]))))

(defn write-report!
  "Render and write `report.md`. Returns the file."
  ^File [^File run-dir manifest]
  (let [f (io/file run-dir report-name)]
    (io/make-parents f)
    (spit f (str (report-md manifest) "\n"))
    f))
