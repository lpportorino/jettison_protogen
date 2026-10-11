(ns press.doc.guide-section-test
  "One section of a long reference guide, written as data, builds: the
   blocks are enough to express a full guide section."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [press.deck :as deck]
            [press.doc :as doc]
            [press.doc.fixture :as fx]
            [press.doc.guide-section :as gs]))

(use-fixtures :each fx/cleanup)

(deftest guide-section-1-builds
  (let [d (fx/doc-dir! gs/document)
        c (doc/check d)]
    (is (= {:ok? true :violations [] :figures []} c))
    (let [r (doc/build! d (str (io/file d "out")))
          html (slurp (:path r))]
      (is (:self-contained? (deck/check html)))
      (testing "the section, its lead and both captioned tables with their widths"
        (is (str/includes? html "<h2>1. At the welcome desk</h2><p class=\"lead\">The pop-up library occupies a borrowed shop"))
        (is (str/includes? html "<caption>Helping visitors.</caption><colgroup><col style=\"width:25%\"><col style=\"width:45%\"><col style=\"width:30%\"></colgroup>"))
        (is (str/includes? html "<caption>Sorting donated books.</caption>"))
        (is (= 12 (count (re-seq #"<tr>" html))) "visitor, donation and source rows include each table header")
        (is (str/includes? html "<span class=\"mono\"><span class=\"nb\">forms/</span><wbr><span class=\"nb\">readers/</span><wbr><span class=\"nb\">new.edn</span></span>")
            "a relative path can break at its directory separators"))
      (testing "the introducing line is kept on the page of its table"
        (is (str/includes? html "<p class=\"keep-next\">The table gives a starting point for each kind of visit:")))
      (testing "terms link to their glossary entries"
        (is (str/includes? html "<a class=\"term\" href=\"#g-reading-card\">reading card</a>"))
        (is (str/includes? html "<div class=\"gl\" id=\"g-condition-slip\">"))))))
