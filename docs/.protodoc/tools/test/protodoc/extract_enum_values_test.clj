(ns protodoc.extract-enum-values-test
  "Round-trip of the '## Values' Description column on enum pages.

   The enum template renders a value's `:description` into the Values table and
   a '-' where there is none. Without a reader for that column the prose an
   author types there is discarded by the next `docs-generate`, and
   `:enum-values-undocumented` can never be satisfied by editing a page — which
   is the only surface an author has."
  (:require [clojure.test :refer [deftest testing is]]
            [protodoc.extract :as extract]))

(defn- write-temp! [content]
  (let [f (java.io.File/createTempFile "protodoc-enum" ".md")]
    (spit f content)
    f))

(def ^:private enum-page
  "---
id: ser.TestStatus
proto: test.proto
package: ser
type: enum
---

# TestStatus

**Source:** `test.proto`

## Description

Parent enum description.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | TEST_STATUS_UNSPECIFIED | - |
| 2 | TEST_STATUS_DEGRADED | Board found but too oblique to solve orientation. |
| 1 | TEST_STATUS_LOCKED | Tracking; the pose fields are valid. |
| 3 | TEST_STATUS_BLANK |  |
")

(deftest extracts-values-table-descriptions-test
  (testing "authored Description cells are read back, keyed by value NUMBER"
    (let [f (write-temp! enum-page)]
      (try
        (let [result (extract/extract-from-file (.getPath f))]
          (is (= "ser.TestStatus" (:id result)))
          ;; Keyed by the number in column 1 — NOT by row order. Row order here
          ;; is 0,2,1,3 deliberately: a positional reader would hand 2's prose
          ;; to 1 and vice versa.
          (is (= {2 "Board found but too oblique to solve orientation."
                  1 "Tracking; the pose fields are valid."}
                 (:values result))
              "only the two authored rows extract, each against its own number")
          ;; '-' is the rendered absence marker and a blank cell is nothing at
          ;; all; neither may read back as documentation.
          (is (not (contains? (:values result) 0)))
          (is (not (contains? (:values result) 3))))
        (finally (.delete f))))))

(deftest values-table-unescapes-pipes-test
  (testing "an escaped pipe returns as a literal pipe, not a cell boundary"
    (let [f (write-temp!
             (str "---\nid: ser.PipeEnum\nproto: test.proto\npackage: ser\ntype: enum\n---\n\n"
                  "## Values\n\n"
                  "| # | Name | Description |\n"
                  "|---|------|-------------|\n"
                  "| 1 | PIPE_ONE | Bitwise OR: `a \\| b` sets both flags. |\n"))]
      (try
        (is (= {1 "Bitwise OR: `a | b` sets both flags."}
               (:values (extract/extract-from-file (.getPath f)))))
        (finally (.delete f))))))

(deftest page-without-values-section-is-unaffected-test
  (testing "a message page carries no :values key"
    (let [f (write-temp!
             (str "---\nid: cmd.Test\nproto: test.proto\npackage: cmd\ntype: message\n---\n\n"
                  "## Description\n\nA message.\n\n"
                  "## Fields\n\n"
                  "| # | Field | Type | Constraints |\n"
                  "|---|-------|------|-------------|\n"
                  "| 1 | value | double | >= 0 |\n"))]
      (try
        (let [result (extract/extract-from-file (.getPath f))]
          (is (= "A message." (:description result)))
          (is (not (contains? result :values))
              "no ## Values section must extract to no :values key at all"))
        (finally (.delete f)))))

  (testing "an untouched enum page (every cell '-') extracts to no :values key"
    (let [f (write-temp!
             (str "---\nid: ser.AllBlank\nproto: test.proto\npackage: ser\ntype: enum\n---\n\n"
                  "## Values\n\n"
                  "| # | Name | Description |\n"
                  "|---|------|-------------|\n"
                  "| 0 | A | - |\n"
                  "| 1 | B | - |\n"))]
      (try
        (is (not (contains? (extract/extract-from-file (.getPath f)) :values)))
        (finally (.delete f))))))

(deftest merge-user-content-assigns-value-descriptions-by-number-test
  (testing "extracted value prose merges onto the enum's values by number"
    (let [db {:messages {}
              :enums {"ser.TestStatus"
                      {:id "ser.TestStatus"
                       :name "TestStatus"
                       :package "ser"
                       :source "test.proto"
                       ;; DB order differs from page order on purpose.
                       :values [{:number 0 :name "TEST_STATUS_UNSPECIFIED"}
                                {:number 1 :name "TEST_STATUS_LOCKED"}
                                {:number 2 :name "TEST_STATUS_DEGRADED"}]}}
              :search-index {}}
          user-content {"ser.TestStatus" {:description "Parent."
                                          :values {2 "Degraded prose."
                                                   1 "Locked prose."}}}
          merged (extract/merge-user-content db user-content)
          by-num (into {} (map (juxt :number identity))
                       (get-in merged [:enums "ser.TestStatus" :values]))]
      (is (= "Parent." (get-in merged [:enums "ser.TestStatus" :description])))
      (is (= "Locked prose." (:description (get by-num 1))))
      (is (= "Degraded prose." (:description (get by-num 2))))
      (is (nil? (:description (get by-num 0)))
          "a value with no authored prose stays undescribed")
      (is (= 3 (count (get-in merged [:enums "ser.TestStatus" :values])))
          "merging adds no values and drops none"))))

(deftest merge-tolerates-values-for-unknown-numbers-test
  (testing "prose for a number the enum no longer has is dropped, not misassigned"
    (let [db {:messages {}
              :enums {"ser.Small" {:id "ser.Small" :name "Small" :package "ser"
                                   :source "test.proto"
                                   :values [{:number 0 :name "A"}]}}
              :search-index {}}
          user-content {"ser.Small" {:values {0 "Zero prose." 99 "Removed member prose."}}}
          merged (extract/merge-user-content db user-content)
          values (get-in merged [:enums "ser.Small" :values])]
      (is (= 1 (count values)))
      (is (= "Zero prose." (:description (first values)))))))
