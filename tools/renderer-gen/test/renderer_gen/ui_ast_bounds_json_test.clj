(ns renderer-gen.ui-ast-bounds-json-test
  "Canary for `renderer-gen.ui-ast-bounds-json` — the emitter whose SUCCESS is
   what publishes `output/manifests/ui-ast-bounds.json`, so its clean output and
   its checked-nothing output are the same file.

   A RED IS NOT ENOUGH; IT MUST NAME ITS CLAUSE. These refusals overlap on
   plausible inputs — a token that is neither `key:value` nor a known option
   would satisfy two of them — so every case below asserts EQUALITY on the
   `:clause` its mutation was aimed at. The emitter refuses on the FIRST clause
   that fires and carries exactly one `:clause`, so that equality is precisely
   the neighbour-silence assertion: any other clause reaching the input first
   fails the case. A case asserting only `thrown?` would accept any refusal,
   including one raised by a neighbour while the clause under test was dead.

   HERMETIC: every clause case is driven over a synthetic options STRING, so the
   suite runs on a dirty checkout, restores nothing, and cannot strand the tree.
   The one case that reads the committed file asserts STRUCTURE only — never a
   value, because a value asserted here would be a second home for a number this
   manifest exists to give exactly one."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [renderer-gen.ui-ast-bounds-json :as bounds]))

(def ^:private green
  "A synthetic options source that must parse: one option per shape the emitter
   publishes. Its GREEN is what makes every red below attributable to the
   mutation rather than to the fixture."
  (str "# a comment\n"
       "\n"
       "ui.Alpha.text      max_size:256\n"
       "ui.Alpha.items     max_count:8\n"
       "ui.Beta.children   type:FT_CALLBACK\n"
       "ui.Beta.pair       max_count:8 max_size:32\n"))

(defn- refusal
  "Run the emitter's manifest build over `source` and return the ex-data of its
   refusal, or nil when it did not refuse."
  [source]
  (try (bounds/manifest source)
       nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- assert-clause
  "Require `source` to be refused by exactly `clause` — the clause under test
   fires, and every other clause this emitter can raise stays silent."
  [clause source]
  (let [data (refusal source)]
    (is (some? data)
        (str "expected a refusal for " clause ", got a clean parse"))
    (is (= clause (:clause data))
        (str "expected clause " clause ", got " (:clause data)))))

(defn- parsed
  "The manifest built from `source`, after asserting it was NOT refused.
   Returns nil when it was, so the caller skips its value assertions instead
   of letting the refusal escape: an escaped exception is an ERROR, and an
   ERROR cannot be told from a broken harness — the whole point of demanding
   a FAIL is that the case says what it expected."
  [source]
  (let [data (refusal source)]
    (is (nil? data) (str "expected a clean parse, was refused: " data))
    (when (nil? data) (bounds/manifest source))))

(deftest green-control-parses
  (testing "the synthetic fixture parses, so a red below is the mutation's"
    (when-some [m (parsed green)]
      (is (= "proto/ui/ui_ast.options" (get m "source")))
      (is (= 4 (count (get m "bounds"))))
      (is (= {"max_size" 256} (get-in m ["bounds" "ui.Alpha.text"])))
      (is (= {"max_count" 8 "max_size" 32} (get-in m ["bounds" "ui.Beta.pair"]))))))

(deftest accepts-a-trailing-comment
  (testing "nanopb tolerates a trailing # comment, so refusing one would red the
            lane over a legal file and blame the wrong token"
    (when-some [m (parsed (str green "ui.Gamma.x   max_size:16  # a note\n"))]
      (is (= {"max_size" 16} (get-in m ["bounds" "ui.Gamma.x"])))
      (is (= 5 (count (get m "bounds")))
          "the commented line must yield an entry, not be skipped"))))

(deftest accepts-the-other-two-nanopb-comment-forms
  (testing "nanopb's read_options_file strips /* */ and // as well as #, all
            three BEFORE text_format.Merge sees the line — so a parser that
            knew only # refused a legal file and blamed the token `//` rather
            than itself. Read from the generator in the pinned image, not
            inferred from the text format, which has neither of these forms."
    (when-some [m (parsed (str green "ui.Gamma.x   max_size:16  // a note\n"))]
      (is (= {"max_size" 16} (get-in m ["bounds" "ui.Gamma.x"]))
          "a // trailing comment must yield the entry, not refuse the line")
      (is (= 5 (count (get m "bounds")))))
    (when-some [m (parsed (str green "ui.Delta.y   max_size:24  /* a note */\n"))]
      (is (= {"max_size" 24} (get-in m ["bounds" "ui.Delta.y"]))
          "a /* */ trailing comment must yield the entry too")
      (is (= 5 (count (get m "bounds")))))))

(deftest refuses-an-option-token-that-is-not-key-value
  (assert-clause :unparseable-option (str green "ui.Gamma.x   max_size\n")))

(deftest refuses-an-unknown-nanopb-option
  (assert-clause :unknown-option (str green "ui.Gamma.x   int_size:IS_16\n")))

(deftest refuses-a-non-integer-count-bound
  (assert-clause :bad-option-value (str green "ui.Gamma.x   max_size:many\n")))

(deftest refuses-a-zero-count-bound
  (testing "zero is parseable and is still not a bound"
    (assert-clause :bad-option-value (str green "ui.Gamma.x   max_count:0\n"))))

(deftest refuses-a-type-that-is-not-an-ft-token
  (assert-clause :bad-option-value (str green "ui.Gamma.x   type:callback\n")))

(deftest refuses-a-field-line-with-no-options
  (assert-clause :no-options (str green "ui.Gamma.x\n")))

(deftest refuses-a-wildcard-declaration
  (testing "a wildcard bound cannot be published per-field, so it is refused"
    (assert-clause :wildcard-field (str green "*.name   max_size:40\n"))))

(deftest refuses-a-duplicate-field
  (testing "a later line would silently overwrite the published bound"
    (assert-clause :duplicate-field (str green "ui.Alpha.text   max_size:512\n"))))

(deftest refuses-an-empty-parse
  (testing "a comment-only source is what a wrong path also produces"
    (assert-clause :empty "# nothing but prose\n\n")))

(deftest publishes-the-committed-options-file-totally
  (testing "every declaration line of the real file yields exactly one entry"
    ;; CWD-relative, the same contract the rest of this seam runs under
    ;; (tools/renderer-gen is the working directory for every alias).
    (let [f (io/file "../../proto/ui/ui_ast.options")
          _ (is (java.io.File/.isFile f)
                "the committed ui_ast.options must be reachable from tools/renderer-gen")
          source (slurp f)
          ;; The line count is derived HERE, independently of the emitter, so
          ;; the equality below is a real comparison rather than the parser
          ;; agreeing with itself. It matches the emitter's own comment rule:
          ;; text before a `#`, blank lines dropped.
          declared (count (remove str/blank?
                                  (map #(str/trim (first (str/split % #"#" 2)))
                                       (str/split-lines source))))
          published (get (parsed source) "bounds")]
      (is (pos? declared) "the options file must declare something")
      (is (= declared (count published))
          "the manifest must carry one entry per declaration line")
      ;; STRUCTURE, never a value: each entry names at least one closed option,
      ;; and a count bound is a positive integer. Asserting 128 here would make
      ;; this file a second home for the number the manifest exists to own.
      (doseq [[field opts] published]
        (is (seq opts) (str field " must declare at least one option"))
        (is (every? bounds/known-options (keys opts))
            (str field " must declare only published options"))
        (doseq [k ["max_size" "max_count"]]
          (when-some [v (get opts k)]
            (is (and (int? v) (pos? v))
                (str field " " k " must be a positive integer"))))))))
