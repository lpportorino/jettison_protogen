(ns press.doc-test
  "The document library end to end over throwaway document directories
   inside the configured root: a fixture document exercising every block kind
   builds to ONE self-contained, stamped page with its generated parts;
   every structural rule is seen refusing; a refused build leaves no page;
   the print command refuses a stale page; the host commands carry
   root-relative paths; the print report is read strictly; the runbook
   holds root-relative paths only; a bound configuration reaches every
   path, command and hashed input name."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [press.config :as config]
            [press.deck :as deck]
            [press.doc :as doc]
            [press.doc.figure :as fig]
            [press.doc.fixture :as fx]
            [press.doc.io :as dio]
            [press.doc.rules :as rules]))

(use-fixtures :each fx/cleanup)

(defn- build-out
  "The build directory of document directory `d`, root-relative."
  [d]
  (str (fx/build-root) "/" (fx/name-of d)))

(deftest the-example-and-the-fixture-build
  (testing "the bare example passes check and has no figures"
    (let [d (fx/doc-dir! (doc/example))]
      (is (= {:ok? true :violations [] :figures []} (doc/check d)))))
  (testing "the fixture (example + one figure) builds to one self-contained, stamped file"
    (let [d (fx/doc-dir! (fx/with-figure) true)
          r (doc/build! d (build-out d))
          html (slurp (:path r))
          v (deck/check html)]
      (is (str/ends-with? (:path r) (str (build-out d) "/" (fx/name-of d) ".html"))
          "a relative out-dir is taken from the configured root")
      (is (= (.length (io/file (:path r))) (:bytes r)))
      (is (= {:sections 2 :figures 1 :glossary 1} (select-keys r [:sections :figures :glossary])))
      (is (:self-contained? v))
      (is (= [] (rules/html-violations html)) "the backstop is quiet on a real page")
      (is (str/includes? html (str "<meta content=\"" (:stamp r) "\" name=\"press-doc-stamp\">")))
      (is (str/includes? html "@page { size: A4;"))
      (is (str/includes? html "figure svg { width: 168mm;") "a figure prints at the column width")
      (testing "the skeleton, in order, with the custom page titles"
        (let [at #(str/index-of html %)]
          (is (< (at "class=\"cover\"") (at "id=\"problem\"") (at "id=\"status\"") (at "id=\"contents\"")
                 (at "id=\"part-1\"") (at "id=\"open-questions\"") (at "id=\"glossary\"") (at "id=\"sources\"")))
          (is (str/includes? html "<h2>The problem: portable pages</h2>"))
          (is (str/includes? html "<h2>Where this stands: built</h2>"))))
      (testing "every block kind rendered"
        (doseq [needle ["<p class=\"lead\"" "<h3 id=\"s-coverage\">1.1 What each input contributes</h3>" "<ul>" "<ol>" "<ol class=\"steps\">"
                        "<caption>Three input kinds; the counts cover the illustrative path only.</caption>" "<col style=\"width:20%\">"
                        "<td class=\"r\">7</td>" "<th class=\"r\">characters</th>"
                        "<div class=\"note\">" "<div class=\"warn\">" "<div class=\"verdict\">"
                        "<p class=\"small muted\">Character counts illustrate" "<em>self-contained</em>"
                        "<span class=\"pill built\">BUILT</span>" "<span class=\"mono\">"
                        "<div class=\"keep item\"><h4>1. A source image may change</h4>"
                        "<h4>2. A network link may disappear</h4>"
                        "<h3>How to read this</h3><p>Read <a class=\"ref\" href=\"#s-inputs\">§ 1</a> first.</p>"]]
          (is (str/includes? html needle) needle)))
      (testing "the cover carries its verdict box before the meta lines"
        (is (< (str/index-of html "<div class=\"verdict\"><p><b>The one idea.")
               (str/index-of html "<div class=\"meta\">"))))
      (testing "a code path breaks only after a slash, never mid-word"
        (is (str/includes? html (str "<span class=\"mono\"><span class=\"nb\">tools/</span><wbr>"
                                     "<span class=\"nb\">press/</span><wbr><span class=\"nb\">src/</span><wbr>"
                                     "<span class=\"nb\">press/</span><wbr><span class=\"nb\">doc.clj</span></span>")))
        (is (str/includes? html "<span class=\"nb\">tools/</span><wbr><span class=\"nb\">press/</span>")))
      (testing "generated parts: numbers, contents links, figure number, glossary, legend, snapshot"
        (is (str/includes? html "<h2>1. Inputs</h2>"))
        (is (str/includes? html "<ol start=\"1\"><li><a href=\"#s-inputs\">Inputs</a></li>"))
        (is (str/includes? html "<b>Figure 1 — </b><b>The hub and a node.</b>"))
        (is (str/includes? html "<a class=\"term\" href=\"#g-input\">input</a>"))
        (is (str/includes? html "<div class=\"gl\" id=\"g-input\"><dt>input</dt>"))
        (is (str/includes? html "Snapshot of the tree at commit <span class=\"mono\">40feb9f965</span>, 2026-10-11."))
        (is (str/includes? html "<span class=\"pill open\">OPEN</span> an unresolved question"))
        (is (not (str/includes? html "<span class=\"pill ruled\">")) "only the marks in use are in the legend"))
      (testing "a paragraph before a table or a figure is kept with it"
        (is (str/includes? html "<p class=\"keep-next\">Three illustrative input paths are listed here.</p>"))
        (is (str/includes? html "<p class=\"keep-next\">The figure shows the hub and one node.</p>")))
      (testing "the figure is re-serialised, its ids scoped to this occurrence"
        (is (str/includes? html "<figure id=\"fig-fig1\"><svg "))
        (is (str/includes? html "<marker id=\"f1-arr\""))
        (is (str/includes? html "marker-end=\"url(#f1-arr)\""))
        (is (not (re-find #"id=\"arr\"" html)))))))

(defn- broken
  "The fixture with `f` applied — for a refusal test."
  [f]
  (f (fx/with-figure)))

(def ^:private table [:body 0 :sections 0 :blocks 3 1])

(deftest every-structural-rule-refuses
  (testing ":doc/skeleton — a missing or empty skeleton part"
    (is (contains? (fx/rules-of (broken #(dissoc % :problem))) :doc/skeleton))
    (is (contains? (fx/rules-of (broken #(assoc % :sources []))) :doc/skeleton)))
  (testing ":doc/lead-first — a page that opens with anything but a lead"
    (is (contains? (fx/rules-of (broken #(update-in % [:body 0 :sections 1 :blocks] (fn [bs] (vec (rest bs))))))
                   :doc/lead-first))
    (is (contains? (fx/rules-of (broken #(assoc % :problem [[:doc/p "no lead"]]))) :doc/lead-first)))
  (testing ":doc/table-cols — no widths, the wrong count, a sum that is not 100"
    (is (contains? (fx/rules-of (broken #(update-in % table dissoc :cols))) :doc/table-cols))
    (is (contains? (fx/rules-of (broken #(assoc-in % (conj table :cols) [30 70]))) :doc/table-cols))
    (is (contains? (fx/rules-of (broken #(assoc-in % (conj table :cols) [20 50 31]))) :doc/table-cols)))
  (testing ":doc/table-align — alignments for the wrong number of columns"
    (is (contains? (fx/rules-of (broken #(assoc-in % (conj table :align) [:l :r]))) :doc/table-align)))
  (testing ":doc/table-ragged — a row with the wrong cell count"
    (is (contains? (fx/rules-of (broken #(update-in % (conj table :rows) conj ["only one"]))) :doc/table-ragged)))
  (testing ":doc/duplicate-id"
    (is (contains? (fx/rules-of (broken #(assoc-in % [:body 0 :sections 1 :id] "inputs"))) :doc/duplicate-id)))
  (testing ":doc/ref-undefined — a cross-reference to no section"
    (is (contains? (fx/rules-of (broken #(update % :problem conj [:doc/p "see " [:doc/ref "nowhere"]])))
                   :doc/ref-undefined)))
  (testing ":doc/fig-twice — a figure placed twice"
    (is (contains? (fx/rules-of (broken #(update-in % [:body 0 :sections 1 :blocks] conj [:doc/fig "fig1" "again"])))
                   :doc/fig-twice)))
  (testing ":doc/term-undefined, :doc/term-unused, :doc/duplicate-term"
    (is (contains? (fx/rules-of (broken #(update % :problem conj [:doc/p [:doc/term "rail"]]))) :doc/term-undefined))
    (is (contains? (fx/rules-of (broken #(update % :glossary conj {:term "rail" :def "a supply."}))) :doc/term-unused))
    (is (contains? (fx/rules-of (broken #(update % :glossary conj {:term "Input" :def "again."}))) :doc/duplicate-term))
    (testing "a use inside another entry's definition does not count as a use"
      (is (contains? (fx/rules-of (broken #(update % :glossary conj {:term "rail" :def "a supply."}
                                                   {:term "board" :def [[:doc/term "rail"] " carrier"]})))
                     :doc/term-unused))))
  (testing ":doc/placeholder — an unfilled template slot in text"
    (is (contains? (fx/rules-of (broken #(update % :sources conj [:doc/p "commit {{SHA}}"]))) :doc/placeholder))
    (is (contains? (fx/rules-of (broken #(update % :sources conj [:doc/p "@@STATUS_PAGE@@"]))) :doc/placeholder)))
  (testing "prose that reads like code or markup is text, not a hazard"
    (is (= #{} (fx/rules-of (broken #(update % :sources conj
                                             [:doc/p "the import (and export) path; <script>x</script> onload=\"y\""]))))))
  (testing ":doc/malformed — an unknown block, raw markup, a misplaced figure (shapes the renderer cannot draw)"
    (doseq [f [#(update % :sources conj [:doc/nope "x"])
               #(update % :sources conj [:doc/raw "<b>x</b>"])
               #(update % :problem conj [:doc/fig "fig1"])
               #(update % :problem conj [:doc/note :note [:doc/fig "fig1" "inside a note"]])
               #(update % :problem conj [:doc/item "x" [:doc/fig "fig1" "inside an item"]])
               #(assoc % :cover [[:doc/p "a paragraph on the cover"]])]]
      (let [r (doc/check (fx/doc-dir! (broken f) true))]
        (is (not (:ok? r)))
        (is (= #{:doc/malformed} (set (map :rule (:violations r)))))
        (is (every? (comp seq :at) (:violations r)))))))

(deftest malformed-values-the-renderer-could-draw-are-refused
  (testing ":doc/malformed — values that would render, so only the schema stands between them and a page"
    (doseq [[why f] [["unknown key" #(assoc % :colour "red")]
                     ["unknown mark" #(update % :problem conj [:doc/p [:doc/pill :maybe]])]
                     ["unknown note kind" #(update % :problem conj [:doc/note :tip [:doc/p "x"]])]
                     ["unknown table key" #(assoc-in % (conj table :widths) [1])]
                     ["negative width" #(assoc-in % (conj table :cols) [-10 60 50])]
                     ["unknown alignment" #(assoc-in % (conj table :align) [:c :l :l])]
                     ["section id with a trailing newline" #(assoc-in % [:body 0 :sections 1 :id] "control\n")]
                     ["commit with a trailing newline" #(assoc-in % [:snapshot :commit] "0123456\n")]
                     ["date with a trailing newline" #(assoc-in % [:snapshot :date] "2026-10-11\n")]
                     ["a language that is not a language tag" #(assoc % :lang "javascript:x")]]]
      (let [r (doc/check (fx/doc-dir! (broken f) true))]
        (is (= #{:doc/malformed} (set (map :rule (:violations r)))) why)))))

(deftest the-backstop-refuses-every-bypass
  (testing "each known bypass, were raw markup ever to reach a page"
    (doseq [raw ["<img src=https://example.invalid/a.png>"
                 "<object data=\"https://example.invalid/a.svg\"></object>"
                 "<div style=\"background-image:image-set('https://example.invalid/a.png' 1x)\">x</div>"
                 "<meta http-equiv=\"refresh\" content=\"0;url=https://example.invalid/\">"
                 "<img src=\"#\" onerror=\"fetch('https://example.invalid/beacon')\">"
                 "<a href=\"javascript:fetch('https://example.invalid/')\">x</a>"
                 "<iframe srcdoc=\"&lt;img src=https://example.invalid/x&gt;\"></iframe>"
                 "<script>1</script>"
                 "<style>@import url(https://example.invalid/a.css);</style>"]]
      (is (seq (rules/html-violations (str "<!DOCTYPE html><html><body><p>x</p>" raw "</body></html>"))) raw)))
  (testing "prose that NAMES a hazard is not one: the patterns look inside tags and style only"
    (is (= #{} (fx/rules-of (broken #(update % :sources conj [:doc/p "CSS image-set( lists candidates"]))))))
  (testing "and a page without them is clean"
    (is (= [] (rules/html-violations "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body><p>x &lt;script&gt;</p></body></html>")))))

(deftest figure-rules-refuse
  (testing "a figure with no description"
    (let [d (fx/doc-dir! (fx/with-figure) false)]
      (is (= [:doc/fig-not-current] (map :rule (:violations (doc/check d)))))))
  (testing "a stale figure refuses the build, which writes nothing and REMOVES an older page"
    (let [d (fx/doc-dir! (fx/with-figure) true)
          out (build-out d)
          page (:path (doc/build! d out))]
      (spit (io/file d "figs" "fig1.edn") (pr-str (assoc-in fx/diagram [:nodes 1 :rows 0 1] "worker 4")))
      (let [r (doc/check d)]
        (is (= [:doc/fig-not-current] (map :rule (:violations r))))
        (is (str/includes? (:message (first (:violations r))) "problem_hash")))
      (is (= :doc/refused (fx/refusal #(doc/build! d out))))
      (is (not (.exists (io/file page))) "the stale page is gone")))
  (testing "a refusal by EXCEPTION removes the old page too (a figure SVG that is a directory throws while reading)"
    ;; A directory in the SVG's place, not a permission bit: a read of it throws for every
    ;; user, where an unreadable file is still readable by root (the container's user).
    (let [d (fx/doc-dir! (fx/with-figure) true)
          out (build-out d)
          page (:path (doc/build! d out))
          svg (io/file d "figs" "fig1.svg")]
      (io/delete-file svg)
      (.mkdir svg)
      (is (thrown? Exception (doc/build! d out)))
      (is (not (.exists (io/file page))) "no page survives a build that threw")))
  (testing "a write that FAILS leaves no temp file behind (the destination is a non-empty directory)"
    (let [d (fx/doc-dir! (fx/with-figure) true)
          out (config/from-root (build-out d))
          blocker (io/file out (str (fx/name-of d) ".html"))]
      (.mkdirs blocker)
      (spit (io/file blocker "x") "x")
      (is (thrown? Exception (doc/build! d (build-out d))) "the move onto a directory throws")
      (is (empty? (filter #(str/starts-with? % ".doc-") (seq (.list (io/file out)))))
          "no temp file left behind")))
  (testing "a current pick whose SVG is outside the renderer's allowlist"
    (let [d (fx/doc-dir! (fx/with-figure) false)]
      (fx/write-fig! d fx/diagram {:svg-text "<svg><script>1</script></svg>"})
      (is (= [:doc/bad-svg] (map :rule (:violations (doc/check d))))))))

(deftest the-stamp-covers-everything-that-shapes-the-page
  (let [d (fx/doc-dir! (fx/with-figure) true)
        names (set (map first (dio/stamp-inputs (io/file d) ["fig1"])))]
    (doseq [n ["doc.edn" "figs/fig1.edn" "figs/fig1.pick.json" "figs/fig1.svg"
               "tools/press/press/house.json" "press/doc/house-print.css" "press/deck/Inter.var.woff2"]]
      (is (contains? names n) n))))

(deftest load-refusals
  (testing "no directory at all is a throw"
    (is (= :doc/no-dir (fx/refusal #(doc/check "/nonexistent/press-doc-dir")))))
  (testing "a directory without doc.edn, or with unreadable EDN, is a violation"
    (let [d (fx/doc-dir! (doc/example))]
      (spit (io/file d "doc.edn") "{:title \"x\"")
      (is (= [:doc/unreadable] (map :rule (:violations (doc/check d)))))
      (io/delete-file (io/file d "doc.edn"))
      (is (= [:doc/no-doc] (map :rule (:violations (doc/check d)))))
      (is (= :doc/refused (fx/refusal #(doc/build! d (build-out d))))))))

(deftest host-commands
  (let [d (fx/doc-dir! (fx/with-figure) true)
        r (fx/rel d)
        b (build-out d)
        nm (fx/name-of d)]
    (testing "fig-solve! writes the canonical JSON and returns the press command with root-relative paths"
      (let [c (doc/fig-solve! d "fig1")]
        (is (= ["tools/press/bin/press" "fig" "solve" (str b "/figs/fig1.fig.json") (str b "/figs/fig1")] (:argv c)))
        (is (= (str/join " " (:argv c)) (:shell c)))
        (is (= (str b "/figs/fig1/sheet.png") (:look-at c)))
        (is (.isFile (io/file (config/from-root (str b "/figs/fig1.fig.json")))) "relative to the configured root")))
    (testing "fig-pick! names the tile and the two committed files"
      (let [c (doc/fig-pick! d "fig1" 3)]
        (is (= ["tools/press/bin/press" "fig" "pick" (str b "/figs/fig1.fig.json") (str b "/figs/fig1") "3"
                (str r "/figs/fig1.pick.json") (str r "/figs/fig1.svg")] (:argv c)))))
    (testing "a tile outside 1..8 is refused at the boundary"
      (is (= :malli/boundary (fx/refusal #(doc/fig-pick! d "fig1" 9)))))
    (testing "a chart is drawn by `press chart <chart.json> <pick.json> <out.svg>` and cannot be picked"
      (fx/write-fig! d {:id "c1" :kind :chart :chart :bar :data {:series [1 2 3]}})
      (let [c (doc/fig-solve! d "c1")]
        (is (= ["tools/press/bin/press" "chart" (str b "/figs/c1.fig.json") (str r "/figs/c1.pick.json")
                (str r "/figs/c1.svg")] (:argv c)))
        (is (= [(str r "/figs/c1.pick.json") (str r "/figs/c1.svg")] (:writes c))))
      (is (= :doc/not-a-diagram (fx/refusal #(doc/fig-pick! d "c1" 1)))))
    (testing "pdf: no page yet, then a current page, then a stale one"
      (is (= :doc/no-page (fx/refusal #(doc/pdf d))))
      (doc/build! d b)
      (let [c (doc/pdf d)]
        (is (= ["tools/press/bin/press" "pdf" (str b "/" nm ".html") (str b "/pdf") "--name" nm] (:argv c)))
        (is (= [["tools/press/bin/press" "fig" "check" (str r "/figs/fig1.svg")] (:argv c)] (:steps c))
            "every placed figure is checked before the print")
        (is (= (str "tools/press/bin/press fig check " r "/figs/fig1.svg && tools/press/bin/press pdf "
                    b "/" nm ".html " b "/pdf --name " nm)
               (:shell c)))
        (is (= [(str b "/pdf/" nm ".pdf") (str r "/" nm ".pdf")] (:writes c))))
      (spit (io/file d "doc.edn") (pr-str (assoc (fx/with-figure) :audience "someone else")))
      (is (= :doc/stale-page (fx/refusal #(doc/pdf d))) "doc.edn changed since the build")
      (doc/build! d b)
      (spit (io/file d "figs" "fig1.svg") (str fx/svg " "))
      (is (= :doc/stale-page (fx/refusal #(doc/pdf d))) "a figure's SVG changed since the build"))
    (testing "an argument with a space is quoted in the shell line"
      (let [d2 (fx/doc-dir! (fx/with-figure) true "my doc")]
        (doc/build! d2 (str (fx/build-root) "/my doc"))
        (is (str/includes? (:shell (doc/pdf d2)) (str "'" (fx/build-root) "/my doc/my doc.html'")))))
    (testing "a document outside the root is refused, not guessed"
      (let [outside (.toFile (java.nio.file.Files/createTempDirectory "press-doc-out" (make-array java.nio.file.attribute.FileAttribute 0)))]
        (spit (io/file outside "doc.edn") (pr-str (doc/example)))
        (is (= :doc/outside-repo (fx/refusal #(doc/runbook! (str outside)))))
        (io/delete-file (io/file outside "doc.edn"))
        (io/delete-file outside)))))

(deftest fig-json-writes-or-refuses
  (let [d (fx/doc-dir! (fx/with-figure) true)
        out (str (io/file d "j"))
        r (doc/fig-json! d "fig1" out)]
    (is (= (str (io/file out "fig1.fig.json")) (:path r)))
    (is (= :diagram (:kind r)))
    (is (= (:problem-hash r) (fig/sha256 (slurp (:path r)))))
    (is (= :doc/bad-figure (fx/refusal #(doc/fig-json! d "nope" out))))
    (is (= :doc/bad-figure (fx/refusal #(doc/fig-json! d "../x" out))))))

(defn- finding
  "A well-formed finding."
  [detector severity allowed]
  {"detector" detector "page" 2 "element" "table#t3" "measure" {"rows_on_next" 4}
   "severity" severity "allowed" allowed})

(deftest pdf-report-is-strict-and-tied-to-the-page
  (let [d (fx/doc-dir! (doc/example))
        nm (fx/name-of d)
        _ (doc/build! d (build-out d))
        page (io/file (config/from-root (str (build-out d) "/" nm ".html")))
        dir (io/file (config/from-root (str (build-out d) "/pdf")))
        sha (fig/sha256 (java.nio.file.Files/readAllBytes (.toPath page)))
        clean {"pdf" (str "/out/" nm ".pdf") "html_sha256" sha "page_count" 3 "blank_pages" []
               "requests_failed" [] "links" {"dead_links" 0 "html_unresolved" []}
               "overflow" {"offenders" []} "layout" {"allowed" []} "findings" []}
        put! (fn [m] (.mkdirs dir) (spit (io/file dir "pdf_report.json") (json/generate-string m)))]
    (testing "a missing report is refused by name, never inferred"
      (is (= :doc/no-report (fx/refusal #(doc/pdf-report d)))))
    (testing "a clean report on THIS page is ok, and the container PDF path is mapped onto the report's directory"
      (put! clean)
      (let [v (doc/pdf-report d)]
        (is (:ok? v))
        (is (= 3 (:pages v)))
        (is (= (str (io/file dir (str nm ".pdf"))) (:pdf v)))))
    (testing "`layout` carries mechanism, join shares and counts beside `allowed`, and no findings list"
      (put! (assoc clean "layout" {"allowed" [] "mechanism" "paged" "join_shares" {"a" 1} "counts" {"blocking" 0}}))
      (is (:ok? (doc/pdf-report d))))
    (testing "a report on another page — or naming none — is :doc/stale-report"
      (put! (assoc clean "html_sha256" (str/join (repeat 64 "0"))))
      (is (= :doc/stale-report (fx/refusal #(doc/pdf-report d))))
      (put! (dissoc clean "html_sha256"))
      (is (= :doc/stale-report (fx/refusal #(doc/pdf-report d)))))
    (testing "every malformed report is a refusal — never :ok? true"
      (doseq [[why m] [["no findings" (dissoc clean "findings")]
                       ["findings null" (assoc clean "findings" nil)]
                       ["findings not a list" (assoc clean "findings" {"a" 1})]
                       ["severity polish is not a value" (assoc clean "findings" [(finding "d" "polish" false)])]
                       ["unknown severity" (assoc clean "findings" [(finding "d" "Blocking" false)])]
                       ["missing severity" (assoc clean "findings" [(dissoc (finding "d" "fix" false) "severity")])]
                       ["string page" (assoc clean "findings" [(assoc (finding "d" "fix" false) "page" "2")])]
                       ["missing measure" (assoc clean "findings" [(dissoc (finding "d" "fix" false) "measure")])]
                       ["missing allowed" (assoc clean "findings" [(dissoc (finding "d" "fix" false) "allowed")])]
                       ["allowed not a boolean" (assoc clean "findings" [(finding "d" "fix" "yes")])]
                       ["allowed but not in layout.allowed" (assoc clean "findings" [(finding "d" "blocking" true)])]
                       ["in layout.allowed but not marked allowed"
                        (assoc clean "layout" {"allowed" ["d"]} "findings" [(finding "d" "blocking" false)])]
                       ["no layout" (dissoc clean "layout")]
                       ["no layout.allowed" (assoc clean "layout" {})]
                       ["finding not an object" (assoc clean "findings" ["x"])]
                       ["no page_count" (dissoc clean "page_count")]
                       ["no links" (dissoc clean "links")]
                       ["pdf outside /out" (assoc clean "pdf" "/etc/x.pdf")]
                       ["not an object" [1 2]]]]
        (put! m)
        (is (= :doc/bad-report (fx/refusal #(doc/pdf-report d))) why))
      (spit (io/file dir "pdf_report.json") "{not json")
      (is (= :doc/bad-report (fx/refusal #(doc/pdf-report d))) "not JSON"))
    (testing "the verdict fails on a blocking finding that was not allowed, and lists the allowed ones apart"
      (put! (assoc clean "findings" [(finding "split-table" "blocking" false) (finding "runt" "info" false)]))
      (let [v (doc/pdf-report d)]
        (is (not (:ok? v)))
        (is (= ["split-table"] (map :detector (:blocking v)))))
      (put! (assoc clean "layout" {"allowed" ["split-table"]}
                   "findings" [(finding "split-table" "blocking" true) (finding "runt" "fix" false)]))
      (let [v (doc/pdf-report d)]
        (is (:ok? v) "an allowed blocking finding and a fix do not fail the print")
        (is (= ["split-table"] (map :detector (:allowed v))))
        (is (= {:detector "split-table" :page 2 :element "table#t3" :measure "{\"rows_on_next\" 4}"
                :severity "blocking" :allowed true}
               (first (:allowed v))))))
    (testing "a dead link, an overflow and a blank page each make it not ok"
      (put! (assoc-in clean ["links" "dead_links"] 1))
      (is (not (:ok? (doc/pdf-report d))))
      (put! (assoc-in clean ["overflow" "offenders"] [{"what" "table.wide" "how" "box"}]))
      (is (= ["table.wide box"] (:overflow (doc/pdf-report d))))
      (put! (assoc clean "blank_pages" ["page-2.png"]))
      (is (not (:ok? (doc/pdf-report d)))))
    (testing "rebuilding the page after the print makes the old report stale"
      (put! clean)
      (spit (io/file d "doc.edn") (pr-str (assoc (doc/example) :audience "someone else")))
      (doc/build! d (build-out d))
      (is (= :doc/stale-report (fx/refusal #(doc/pdf-report d)))))))

(defn- absolute-path?
  "Does `text` name the configured root, or any path that starts at `/`?"
  [text]
  (boolean (or (str/includes? text (config/root))
               (re-find #"(?m)(?:^|[\s`'\"(=])/[A-Za-z0-9._-]" text))))

(deftest the-absolute-path-probe-can-fire
  (is (absolute-path? "run /opt/x/press here"))
  (is (absolute-path? (str "see `" (config/from-root "docs") "`")))
  (is (not (absolute-path? "solve / look / pick in docs/guide/figs, then a/b"))))

(deftest runbook-holds-root-relative-paths-only
  (let [d (fx/doc-dir! (fx/with-figure) true)
        r (fx/rel d)
        b (build-out d)
        nm (fx/name-of d)
        text (slurp (:path (doc/runbook! d)))]
    (doseq [needle ["# How to rebuild \"How Press builds one portable document\""
                    (str "(doc/check \"" r "\")")
                    "### Figure `fig1`"
                    (str "(doc/fig-solve! \"" r "\" \"fig1\")")
                    (str "tools/press/bin/press fig solve " b "/figs/fig1.fig.json " b "/figs/fig1")
                    (str "tools/press/bin/press fig check " r "/figs/fig1.svg")
                    (str "tools/press/bin/press pdf " b "/" nm ".html " b "/pdf --name " nm)
                    (str "cp " b "/pdf/" nm ".pdf " r "/" nm ".pdf")
                    "5 the figure is taller than a page" "7 a fault in the layout tool itself" "a clean print report is not a page review"
                    "evaluate it in a Clojure REPL" "`tools/press/bin/press`, in a container"
                    "Build its image once with `make -f press.mk image`."]]
      (is (str/includes? text needle) needle))
    (is (not (absolute-path? text)) "no absolute path")
    (is (= text (do (doc/runbook! (fx/rel d)) (slurp (io/file d "RUNBOOK.md"))))
        "the same bytes when generated from a relative path"))
  (testing "the RUNBOOK's check and print lines are exactly the steps `pdf` returns; an unplaced draft is not checked"
    (let [d (fx/doc-dir! (fx/with-figure) true)]
      (fx/write-fig! d (assoc fx/diagram :id "draft"))
      (doc/build! d (build-out d))
      (let [text (slurp (:path (doc/runbook! d)))
            steps (:steps (doc/pdf d))]
        (doseq [st steps] (is (str/includes? text (str "    " (str/join " " st))) (str/join " " st)))
        (is (not (str/includes? text "figs/draft.svg")) "the draft is not checked")
        (is (str/includes? text "`draft` is not placed in the document")))))
  (testing "a figure that cannot be solved yet says why; a chart says it is drawn in one step"
    (let [d (fx/doc-dir! (fx/with-figure) false)]
      (spit (io/file (doto (io/file d "figs") .mkdirs) "fig1.edn") "{:id \"fig1\" :kind :diagram}")
      (fx/write-fig! d {:id "c1" :kind :chart :chart :bar :data {:series [1]}})
      (let [text (slurp (:path (doc/runbook! d)))]
        (is (str/includes? text "# not runnable yet"))
        (is (str/includes? text "A chart is drawn in one step"))
        (is (not (absolute-path? text)) "no absolute path in the refusal reason either"))))
  (testing "no doc.edn, no runbook"
    (let [d (fx/doc-dir! (doc/example))]
      (io/delete-file (io/file d "doc.edn"))
      (is (= :doc/no-doc (fx/refusal #(doc/runbook! d)))))))

(deftest glossary-letter-is-the-first-code-point
  (let [d (fx/doc-dir! (-> (doc/example)
                           (update :glossary conj {:term "😀 mark" :def "a face."})
                           (update :problem conj [:doc/p [:doc/term "😀 mark"]])))
        html (slurp (:path (doc/build! d (build-out d))))]
    (is (str/includes? html "<p class=\"keep-next\"><b>😀</b></p>"))))

(deftest glossary-anchors-never-collide
  (testing "two Cyrillic terms get distinct anchors (an ASCII slug would map both to one)"
    (let [d (fx/doc-dir! (-> (doc/example)
                             (update :glossary conj {:term "шина" :def "a bus."} {:term "вузол" :def "a node."})
                             (update :problem conj [:doc/p [:doc/term "шина"] " and " [:doc/term "вузол"]])))
          html (slurp (:path (doc/build! d (build-out d))))
          ids (map second (re-seq #"id=\"(g-[^\"]+)\"" html))]
      (is (= 3 (count ids)))
      (is (= (count ids) (count (distinct ids)))))))

(deftest breakable-table-captions-stay-before-their-table
  (doseq [breakable [true false]]
    (let [data (update-in (doc/example) [:body 0 :sections 0 :blocks] conj
                          [:doc/table {:cols [100] :head ["Entry"] :rows [["One"]]
                                       :caption [[:b "Inventory"] " at a glance"] :breakable breakable}])
          d (fx/doc-dir! data)
          html (slurp (:path (doc/build! d (build-out d))))]
      (if breakable
        (do (is (str/includes? html "<div class=\"tblock\"><p class=\"keep-next tcap\"><b>Inventory</b> at a glance</p><table class=\"breakable\">"))
            (is (not (str/includes? html "<caption><b>Inventory</b> at a glance</caption>"))))
        (is (str/includes? html "<caption><b>Inventory</b> at a glance</caption>"))))))
