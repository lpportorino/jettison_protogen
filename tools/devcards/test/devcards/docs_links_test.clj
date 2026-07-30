(ns devcards.docs-links-test
  "Every relative link in the generated doc tree must resolve to something on
   disk.

   WHY THIS EXISTS. The widget pages carried two cross-links — `ui_ast.proto`
   and the protodoc index — whose hrefs were computed for a
   `docs/widgets/<WIDGET>/` layout, three segments below the repo root. The
   pages are actually written to `tools/devcards/docs/widgets/<WIDGET>/`, five
   segments down, so both resolved to paths that have never existed
   (`tools/devcards/proto/...` and `tools/devcards/docs/index.md`). All 22
   widget pages shipped both links dead, under a namespace docstring asserting
   the pair was `documented not guessed`.

   Nothing caught it because nothing looked. A markdown link is inert: no
   compiler resolves it, the pages render fine with it broken, and the only
   reader who finds out is a person who clicks. This test is that reader.

   JUDGED AGAINST THE REAL COMMITTED TREE, not against strings the test builds.
   A link checker fed its own fixtures proves the regex works, which is not the
   question — the question is whether the tree on disk is sound. The non-vacuity
   guards below exist because this suite's pass value is otherwise identical to
   its nothing-found value: an empty doc tree, or a regex that matched no links,
   would report clean."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io File)))

(def ^:private docs-root
  "The generated documentation tree, relative to this tool's own directory —
   which is the cwd the test runner uses."
  (io/file "docs"))

(defn- markdown-files
  "Every .md under the generated docs tree."
  []
  (->> (file-seq docs-root)
       (filter File/.isFile)
       (filter #(str/ends-with? (File/.getName %) ".md"))))

(def ^:private link-pattern
  ;; [text](href) — href captured up to the closing paren. Angle-bracket and
  ;; title forms are not emitted by this generator; if one ever is, this pattern
  ;; simply will not match it, which the non-vacuity count below would surface
  ;; only if it matched NOTHING at all. Kept deliberately narrow over clever.
  #"\[[^\]]*\]\(([^)]+)\)")

(defn- relative-links
  "Every relative link target in `file`, as [href resolved-File] pairs. Absolute
   URLs and pure in-page anchors are not filesystem claims and are skipped."
  [^File file]
  (let [dir (File/.getParentFile file)]
    (->> (re-seq link-pattern (slurp file))
         (map second)
         (remove #(re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" %))
         (remove #(str/starts-with? % "#"))
         (map (fn [href]
                (let [path (first (str/split href #"#"))]
                  [href (io/file dir path)])))
         (remove (fn [[_ ^File f]] (str/blank? (File/.getPath f)))))))

(deftest doc-tree-is-not-empty
  (testing "the generated doc tree exists and carries pages"
    (is (File/.isDirectory docs-root)
        "docs/ is missing — every assertion below would pass over nothing")
    (is (<= 20 (count (markdown-files)))
        "fewer .md pages than the widget corpus alone should produce")))

(deftest links-were-actually-found
  (testing "the link pattern matches something"
    ;; The non-vacuity guard proper. Without it, a pattern that stopped matching
    ;; would turn every resolution assertion below into a loop over an empty
    ;; sequence, and this suite would go green over a tree full of dead links.
    (let [total (reduce + (map (comp count relative-links) (markdown-files)))]
      (is (<= 50 total)
          (str "only " total " relative links found across the doc tree — the "
               "pattern is broken, not the tree")))))

(deftest every-relative-link-resolves
  (testing "no generated page points at a path that does not exist"
    (let [broken (for [f (markdown-files)
                       [href ^File target] (relative-links f)
                       :when (not (File/.exists target))]
                   (str (File/.getPath f) " -> " href))]
      (is (empty? broken)
          (str "dead relative link(s):\n  " (str/join "\n  " (sort broken)))))))
