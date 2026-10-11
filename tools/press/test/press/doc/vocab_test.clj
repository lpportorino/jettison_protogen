(ns press.doc.vocab-test
  "The vocabulary a long guide needs beyond a short document, each piece
   seen rendering and seen refused: sub-headings with ids, labels, code and
   status marks; `[:doc/ref id]` to a sub-heading and to a skeleton page;
   `[:doc/fig-ref id]`; a status mark with a qualifier; the `:doc/keep`
   block; and the introducing line — a paragraph ending in a colon — that
   is always kept with what it introduces and refused when it introduces
   nothing."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [press.doc :as doc]
            [press.doc.fixture :as fx]))

(use-fixtures :each fx/cleanup)

(defn- html-of
  "The page `d` builds to (with `fig1` written), as text."
  [d]
  (let [dir (fx/doc-dir! d true)]
    (slurp (:path (doc/build! dir (str (fx/build-root) "/" (fx/name-of dir)))))))

(defn- in-inputs
  "The fixture with `bs` appended to its first section."
  [& bs]
  (update-in (fx/with-figure) [:body 0 :sections 0 :blocks] into bs))

(deftest a-sub-heading-has-an-id-a-number-and-a-reference
  (testing "[:doc/ref id] to a sub-heading prints its number and links to it — forwards too"
    (let [html (html-of (-> (in-inputs [:doc/h3 {:id "limits"} "Limits"] [:doc/p "Each input has one."])
                            (update :problem conj [:doc/p "The limits are in " [:doc/ref "limits"] "."])))]
      (is (str/includes? html "<h3 id=\"s-limits\">1.2 Limits</h3>"))
      (is (str/includes? html "The limits are in <a class=\"ref\" href=\"#s-limits\">§ 1.2</a>."))))
  (testing "a labelled sub-heading prints its label, takes no number, and a reference prints the label"
    (let [html (html-of (-> (in-inputs [:doc/h3 {:id "step-0" :label "Step 0"} "Decide"] [:doc/p "First."]
                                       [:doc/h3 {:id "after"} "After"] [:doc/p "Then."])
                            (update :problem conj [:doc/p "Start at " [:doc/ref "step-0"] ", then " [:doc/ref "after"] "."])))]
      (is (str/includes? html "<h3 id=\"s-step-0\">Step 0 — Decide</h3>"))
      (is (str/includes? html "<h3 id=\"s-after\">1.2 After</h3>") "the labelled heading consumed no number")
      (is (str/includes? html "Start at <a class=\"ref\" href=\"#s-step-0\">Step 0</a>, then <a class=\"ref\" href=\"#s-after\">§ 1.2</a>."))))
  (testing "a sub-heading inside a container is numbered in reading order"
    (let [html (html-of (in-inputs [:doc/keep [:doc/h3 {:id "kept"} "Kept"] [:doc/p "Together."]]
                                   [:doc/p "See " [:doc/ref "kept"] "."]))]
      (is (str/includes? html "<div class=\"keep\"><h3 id=\"s-kept\">1.2 Kept</h3><p>Together.</p></div>"))
      (is (str/includes? html "See <a class=\"ref\" href=\"#s-kept\">§ 1.2</a>."))))
  (testing "a skeleton page is referenced by its name and prints its title"
    (let [html (html-of (update (fx/with-figure) :status conj
                                [:doc/p "See " [:doc/ref "open-questions"] ", " [:doc/ref "problem"] ", "
                                 [:doc/ref "glossary"] " and " [:doc/ref "sources"] "."]))]
      (is (str/includes? html (str "See <a class=\"ref\" href=\"#open-questions\">Open questions</a>, "
                                   "<a class=\"ref\" href=\"#problem\">The problem: portable pages</a>, "
                                   "<a class=\"ref\" href=\"#glossary\">Appendix A</a> and "
                                   "<a class=\"ref\" href=\"#sources\">Appendix B</a>.")))))
  (testing ":doc/duplicate-id — an id shared by sub-headings, by a sub-heading and a section, or taken from a skeleton page"
    (is (contains? (fx/rules-of (in-inputs [:doc/h3 {:id "a"} "One"] [:doc/h3 {:id "a"} "Two"])) :doc/duplicate-id))
    (is (contains? (fx/rules-of (in-inputs [:doc/h3 {:id "build"} "Clash"])) :doc/duplicate-id))
    (is (contains? (fx/rules-of (in-inputs [:doc/h3 {:id "glossary"} "Clash"])) :doc/duplicate-id))
    (is (contains? (fx/rules-of (assoc-in (fx/with-figure) [:body 0 :sections 1 :id] "sources")) :doc/duplicate-id)))
  (testing ":doc/subhead-unnumbered — an id on a page without section numbers needs a label"
    (is (contains? (fx/rules-of (update (fx/with-figure) :problem conj [:doc/h3 {:id "why"} "Why"]))
                   :doc/subhead-unnumbered))
    (is (= #{} (fx/rules-of (update (fx/with-figure) :problem conj [:doc/h3 {:id "why" :label "Why"} "it matters"])))))
  (testing ":doc/malformed — an unknown heading option, an id that is not an anchor, a reference inside a heading"
    (doseq [b [[:doc/h3 {:colour "red"} "x"] [:doc/h3 {:id "Not An Id"} "x"] [:doc/h3 {:id "x"}]
               [:doc/h3 "x " [:doc/ref "inputs"]]]]
      (is (= #{:doc/malformed} (fx/rules-of (in-inputs b))) (pr-str b)))))

(deftest a-figure-is-referenced-by-number
  (testing "[:doc/fig-ref id] prints the figure's number and links to it, before the figure too"
    (let [html (html-of (update (fx/with-figure) :problem conj [:doc/p [:doc/fig-ref "fig1"] " draws it."]))]
      (is (str/includes? html "<a class=\"ref\" href=\"#fig-fig1\">Figure 1</a> draws it."))
      (is (str/includes? html "<b>Figure 1 — </b><b>The hub and a node.</b>"))))
  (testing ":doc/fig-ref-undefined — a figure nobody places, and a cover figure (it has no number)"
    (is (contains? (fx/rules-of (update (fx/with-figure) :problem conj [:doc/p [:doc/fig-ref "nope"]]))
                   :doc/fig-ref-undefined))
    (is (contains? (fx/rules-of (-> (doc/example)
                                    (update :cover conj [:doc/fig "fig1" "on the cover"])
                                    (update :problem conj [:doc/p [:doc/fig-ref "fig1"]])))
                   :doc/fig-ref-undefined))))

(deftest a-sub-heading-carries-code-and-status-marks
  (let [html (html-of (in-inputs [:doc/h3 "Build before the review " [:doc/pill :built "page half"] " " [:doc/pill :proposed]]
                                 [:doc/p "First."]
                                 [:doc/h3 "The reader half: " [:doc/code "tools/press/doc"]]
                                 [:doc/p "Then, " [:doc/pill :measured "trial"] "."]))]
    (testing "a mark with a qualifier prints the qualifier before the mark's name"
      (is (str/includes? html (str "<h3>1.2 Build before the <span class=\"nb\">review <span class=\"pill built\">page half BUILT</span> "
                                   "<span class=\"pill proposed\">PROPOSED</span></span></h3>")))
      (is (str/includes? html "Then, <span class=\"pill measured\">trial MEASURED</span>.")))
    (testing "a code span in a heading"
      (is (str/includes? html "<h3>1.3 The reader half: <span class=\"mono\">")))
    (testing "a mark used only in a heading is in the legend"
      (is (str/includes? html "<span class=\"pill proposed\">PROPOSED</span> proposed by this document"))))
  (testing ":doc/malformed — a qualifier that is not text, an empty one"
    (is (= #{:doc/malformed} (fx/rules-of (in-inputs [:doc/p [:doc/pill :built 3]]))))
    (is (= #{:doc/malformed} (fx/rules-of (in-inputs [:doc/p [:doc/pill :built ""]]))))))

(deftest blocks-are-kept-together
  (testing ":doc/keep holds its blocks on one page"
    (let [html (html-of (in-inputs [:doc/keep [:doc/p "Kept one."] [:doc/ul "a" "b"]]))]
      (is (str/includes? html "<div class=\"keep\"><p class=\"keep-next\">Kept one.</p><ul><li>a</li><li>b</li></ul></div>"))))
  (testing "a paragraph ending in a colon is kept with whatever block it introduces"
    (let [html (html-of (in-inputs [:doc/p "The warning reads:"] [:doc/note :warn [:doc/p "Hot glass."]]
                                   [:doc/p [:b "Kept as a unit:"]] [:doc/keep [:doc/p "One."] [:doc/p "Two."]]))]
      (is (str/includes? html "<p class=\"keep-next\">The warning reads:</p><div class=\"warn\">"))
      (is (str/includes? html "<p class=\"keep-next\"><b>Kept as a unit:</b></p><div class=\"keep\">"))))
  (testing "the rules reach inside a keep block"
    (is (contains? (fx/rules-of (in-inputs [:doc/keep [:doc/table {:head ["a"] :rows [["1"]]}]])) :doc/table-cols)))
  (testing ":doc/intro-dangling — a line ending in a colon that introduces nothing"
    (is (contains? (fx/rules-of (in-inputs [:doc/p "The list follows:"])) :doc/intro-dangling) "last block")
    (is (contains? (fx/rules-of (in-inputs [:doc/p "The list follows:"] [:doc/p "No list."])) :doc/intro-dangling))
    (is (contains? (fx/rules-of (in-inputs [:doc/p "The list follows:"] [:doc/h3 "A heading"] [:doc/p "x"]))
                   :doc/intro-dangling))
    (is (contains? (fx/rules-of (in-inputs [:doc/note :note [:doc/p "Inside a note:"]])) :doc/intro-dangling))
    (is (= #{} (fx/rules-of (in-inputs [:doc/p "The list follows:"] [:doc/ul "a" "b"])))))
  (testing ":doc/malformed — a figure inside a keep block, an empty keep block"
    (is (= #{:doc/malformed} (fx/rules-of (in-inputs [:doc/keep [:doc/fig "fig1" "inside"]]))))
    (is (= #{:doc/malformed} (fx/rules-of (in-inputs [:doc/keep]))))))

(deftest a-long-table-may-break-between-rows
  (testing "a table marked :breakable prints with the class that lets it break between rows"
    (let [html (html-of (in-inputs [:doc/table {:cols [50 50] :breakable true :head ["a" "b"] :rows [["1" "2"]]}]
                                   [:doc/table {:cols [50 50] :head ["c" "d"] :rows [["3" "4"]]}]
                                   [:doc/table {:cols [20 20 15 15 15 15] :breakable true
                                                :head ["a" "b" "c" "d" "e" "f"] :rows [["1" "2" "3" "4" "5" "6"]]}]))]
      (is (str/includes? html "<table class=\"breakable\"><colgroup>"))
      (is (str/includes? html "<table><colgroup>") "an unmarked table keeps the house rule: never split")
      (is (str/includes? html "<table class=\"wide breakable\"><colgroup>"))))
  (testing ":doc/malformed — :breakable that is not a boolean"
    (is (= #{:doc/malformed}
           (fx/rules-of (in-inputs [:doc/table {:cols [100] :breakable "yes" :head ["a"] :rows [["1"]]}]))))))

(deftest a-status-mark-never-wraps-alone
  (testing "the last word of a sub-heading travels with its trailing status marks"
    (let [html (html-of (in-inputs [:doc/h3 {:id "power"} "The built page can render without a network " [:doc/pill :proposed]]
                                   [:doc/p "x"]
                                   [:doc/h3 "Real time " [:doc/pill :measured "counts"] " " [:doc/pill :open "timing"]]
                                   [:doc/p "y"]))]
      (is (str/includes? html (str "<h3 id=\"s-power\">1.2 The built page can render without a "
                                   "<span class=\"nb\">network <span class=\"pill proposed\">PROPOSED</span></span></h3>")))
      (is (str/includes? html (str "1.3 Real <span class=\"nb\">time <span class=\"pill measured\">counts MEASURED</span> "
                                   "<span class=\"pill open\">timing OPEN</span></span></h3>"))))))

(deftest numbers-and-code-break-only-where-a-reader-expects
  (testing "a number keeps its unit on its line: a no-break space joins them"
    (let [html (html-of (in-inputs [:doc/p "A 100 ms scan, a 16 KiB page, 2 s later, 50 % load, 3 files, 5 seconds."]))]
      (is (str/includes? html "A 100\u00a0ms scan, a 16\u00a0KiB page, 2\u00a0s later, 50\u00a0% load, 3 files, 5 seconds."))))
  (testing "in a table cell a long code piece may break before a hyphen, never after one; a short one never breaks"
    (let [html (html-of (in-inputs [:doc/table {:cols [50 50] :head ["a" "b"]
                                                :rows [[[:doc/code "tools/bench/relay-controller/"] [:doc/code "break-on!"]]]}]))]
      (is (str/includes? html (str "<span class=\"mono\"><span class=\"nb\">tools/</span><wbr><span class=\"nb\">bench/</span><wbr>"
                                   "<span class=\"nb\">relay</span><wbr><span class=\"nb\">-controller/</span></span>")))
      (is (str/includes? html "<span class=\"mono\"><span class=\"nb\">break-on!</span></span>"))))
  (testing "in prose a piece between slashes is never cut, hyphens included"
    (let [html (html-of (in-inputs [:doc/p "See " [:doc/code "tools/bench/relay-controller/"] "."]))]
      (is (str/includes? html "<wbr><span class=\"nb\">relay-controller/</span></span>")))))

(deftest a-glossary-term-can-be-code
  (testing "an entry with :code true prints its term, and every use of it, in the code face — still linked"
    (let [html (html-of (-> (in-inputs [:doc/p "Each run gets a fresh " [:doc/term "batch_id"] "."])
                            (update :glossary conj {:term "batch_id" :code true
                                                    :def "a number each sowing is given."})))]
      (is (str/includes? html "Each run gets a fresh <a class=\"term mono\" href=\"#g-batch_5f_id\">batch_id</a>."))
      (is (str/includes? html "<dt><span class=\"mono\">batch_id</span></dt>"))
      (is (str/includes? html "<dt>input</dt>") "an entry without :code keeps the text face")))
  (testing "a use spelt by an :also of a code entry is code too"
    (let [html (html-of (-> (in-inputs [:doc/p "The unit is " [:doc/term "library-catalog.service"] "."])
                            (update :glossary conj {:term "catalog" :also ["library-catalog.service"] :code true
                                                    :def "the unit name."})))]
      (is (str/includes? html "<a class=\"term mono\" href=\"#g-catalog\">library-catalog.service</a>"))))
  (testing ":doc/malformed — :code that is not a boolean"
    (is (= #{:doc/malformed}
           (fx/rules-of (-> (in-inputs [:doc/p [:doc/term "batch_id"]])
                            (update :glossary conj {:term "batch_id" :code "yes" :def "x."})))))))
