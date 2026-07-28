(ns lvgl-codegen.font-metrics-test
  "Guards for the compiled-font-metrics emitter, and the PROVENANCE
   DISCRIMINATOR that makes its numbers a measurement rather than a claim.

   A font metric that does not record which rasterizer produced it is not a
   measurement, and `renderer/assets/fonts/*.ttf` is not the rasterizer: the
   names `resolve_font` answers return `lv_font_conv` C tables. So this suite
   reproduces — HERE, never in the emitter — the exact derivation
   `lv_tiny_ttf.c` would perform on those same TTFs
   (`stbtt_GetFontVMetrics` = raw `hhea` ascender/descender/lineGap, scaled by
   `stbtt_ScaleForMappingEmToPixels` = px / `head`.unitsPerEm, then TRUNCATED
   by a C cast) and asserts the emitted numbers are NOT that.

   WHY THE DISCRIMINATOR IS ON THE PAIR, and it matters: over the eight custom
   faces in this tree the emitted `base_line` AGREES with the TTF-derived value
   for most of them, and `line_height` agrees for a couple. Either field alone
   is therefore a discriminator that PASSES on a TTF-derived emitter for part
   of the set — the quiet failure this whole namespace exists to make loud. The
   (`line_height`, `base_line`) pair separates every one of them, and the
   difference runs in BOTH directions, which is the executable form of \"no
   correction factor rescues a TTF-derived number\".

   The suite never writes a file and never touches the renderer tree."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lvgl-codegen.font-metrics :as fm])
  (:import (java.io ByteArrayOutputStream File)))

(set! *warn-on-reflection* true)

(def ^:private repo-root fm/repo-root-default)

(defn- tokens [] (edn/read-string (slurp fm/tokens-default)))

(defn- manifest [] (fm/font-metrics {:repo-root repo-root :tokens (tokens)}))

(defn- compiled-records [m]
  (filterv #(= "compiled-table" (:resolution %)) (:fonts m)))

;; ═══════════════════════════════════════════════════════════════════════════
;; The TTF side — present ONLY here, so the emitter provably has no TTF reader
;; ═══════════════════════════════════════════════════════════════════════════

(defn- file-bytes ^bytes [^File f]
  (with-open [in (io/input-stream f)
              out (ByteArrayOutputStream.)]
    (io/copy in out)
    (.toByteArray out)))

(defn- u8 [^bytes b i] (bit-and (int (aget b (int i))) 0xFF))
(defn- u16 [^bytes b i] (bit-or (bit-shift-left (u8 b i) 8) (u8 b (inc (int i)))))
(defn- s16 [^bytes b i] (let [v (u16 b i)] (if (<= 0x8000 v) (- v 0x10000) v)))
(defn- u32 [^bytes b i] (bit-or (bit-shift-left (u16 b i) 16) (u16 b (+ (int i) 2))))

(defn- sfnt-tables
  "The sfnt table directory: 4-char tag → file offset."
  [^bytes b]
  (into {} (for [i (range (u16 b 4))
                 :let [e (+ 12 (* 16 i))]]
             [(String. b (int e) (int 4) "ISO-8859-1") (u32 b (+ e 8))])))

(defn- ttf-vmetrics
  "`head`.unitsPerEm plus `hhea`.{ascender,descender,lineGap} — exactly the
   fields `stbtt_GetFontVMetrics` / `stbtt_ScaleForMappingEmToPixels` read."
  [^File f]
  (let [b (file-bytes f)
        t (sfnt-tables b)
        head (get t "head")
        hhea (get t "hhea")]
    (when (and head hhea)
      {:units-per-em (u16 b (+ head 18))
       :ascent (s16 b (+ hhea 4))
       :descent (s16 b (+ hhea 6))
       :line-gap (s16 b (+ hhea 8))})))

(defn- tiny-ttf-metrics
  "lv_tiny_ttf.c's own arithmetic, float maths and C truncation included:
     scale       = size / unitsPerEm
     line_height = (int32_t)(scale * (ascent - descent + lineGap))
     base_line   = (int32_t)(scale * (lineGap - descent))"
  [{:keys [units-per-em ascent descent line-gap]} size]
  (let [scale (float (/ (float size) (float units-per-em)))]
    {:line-height (long (float (* scale (float (- (+ ascent line-gap) descent)))))
     :base-line (long (float (* scale (float (- line-gap descent)))))}))

(defn- normalize-face
  "Fold a family name and a TTF basename onto one key: the asset filenames are
   canonically cased (`Orbitron-Bold.ttf`) while the font names are snake_case
   (`orbitron_bold_28`), and hardcoding that correspondence would be one more
   place for it to rot."
  [s]
  (str/lower-case (str/replace (str s) #"[^A-Za-z0-9]" "")))

(defn- vendored-faces
  "normalized-face → vmetrics, for every TTF actually vendored in the tree."
  []
  (let [^File d (io/file repo-root fm/asset-font-dir)]
    (into {}
          (keep (fn [^File f]
                  (let [n (.getName f)]
                    (when (str/ends-with? (str/lower-case n) ".ttf")
                      (when-let [v (ttf-vmetrics f)]
                        [(normalize-face (subs n 0 (- (count n) 4))) v])))))
          (or (seq (.listFiles d)) []))))

(defn- ttf-comparisons
  "Every emitted compiled record whose face IS vendored here, paired with what
   lv_tiny_ttf would have produced for it."
  [m]
  (let [faces (vendored-faces)]
    (keep (fn [{:keys [family line-height base-line] :as r}]
            (when-let [v (get faces (normalize-face family))]
              (when-let [size (get-in r [:banner :size])]
                (assoc (tiny-ttf-metrics v size)
                       :name (:name r)
                       :compiled-line-height line-height
                       :compiled-base-line base-line))))
          (compiled-records m))))

;; ═══════════════════════════════════════════════════════════════════════════
;; THE DISCRIMINATOR
;; ═══════════════════════════════════════════════════════════════════════════

(deftest compiled-metrics-are-provably-not-ttf-derived
  ;; REVERT-TO-BREAK: make `font-metrics` emit the tiny-ttf-derived numbers
  ;; (e.g. override parse-table-text's :line-height/:base-line). This test must
  ;; then FAIL naming the colliding fonts, while every other test in this
  ;; namespace stays GREEN — those are the controls.
  (let [cmp (ttf-comparisons (manifest))]
    (testing "the comparison set is non-empty — an empty one would pass vacuously"
      (is (seq cmp)
          (str "no emitted compiled font's face is vendored under "
               fm/asset-font-dir " — this test could not look, which is a finding")))
    (testing "no emitted (line_height, base_line) pair equals its TTF-derived pair"
      (let [collisions (filterv #(and (= (:compiled-line-height %) (:line-height %))
                                      (= (:compiled-base-line %) (:base-line %)))
                                cmp)]
        (is (empty? collisions)
            (str "these fonts' emitted metrics are indistinguishable from a "
                 "TTF-derived emitter's output: "
                 (pr-str (mapv :name collisions))))))
    (testing "the disagreement runs in BOTH directions — no correction factor rescues a TTF-derived number"
      (let [over (filterv #(< (:line-height %) (:compiled-line-height %)) cmp)
            under (filterv #(> (:line-height %) (:compiled-line-height %)) cmp)]
        (is (seq over)
            (str "expected at least one compiled line_height ABOVE its TTF-derived "
                 "value; got none over " (pr-str (mapv :name cmp))))
        (is (seq under)
            (str "expected at least one compiled line_height BELOW its TTF-derived "
                 "value; got none over " (pr-str (mapv :name cmp))))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Totality — every drawable font is accounted for, none silently dropped
;; ═══════════════════════════════════════════════════════════════════════════

(deftest every-resolvable-font-is-emitted-exactly-once
  (let [m (manifest)
        emitted (mapv :name (:fonts m))
        {:keys [arms]} (fm/resolve-font-arms repo-root)
        strcmp-names (set (map :name arms))
        declared-names (set (map :name (fm/declared-tuples (tokens))))]
    (testing "renderer.c actually yielded arms — a zero-arm parse must not read as clean"
      (is (seq strcmp-names))
      (is (seq declared-names)))
    (testing "no duplicate records"
      (is (= (count emitted) (count (set emitted))) (pr-str emitted)))
    (testing "every name resolve_font string-matches is emitted"
      (is (empty? (set/difference strcmp-names (set emitted)))))
    (testing "every declared tokens.edn tuple is emitted"
      (is (empty? (set/difference declared-names (set emitted)))))
    (testing "nothing is emitted that is neither string-matched nor declared"
      (is (empty? (set/difference (set emitted)
                                  (set/union strcmp-names declared-names)))))
    (testing "records are in a deterministic (sorted) order"
      (is (= (vec (sort emitted)) emitted)))
    (testing "exactly one record is flagged as resolve_font's fallback, and it is compiled"
      (let [fb (filterv :renderer-fallback (:fonts m))]
        (is (= 1 (count fb)) (pr-str (mapv :name fb)))
        (is (= (:renderer-fallback m) (:name (first fb))))
        (is (= "compiled-table" (:resolution (first fb))))))))

(deftest every-declared-token-is-attributed-to-its-font
  (let [m (manifest)
        attributed (into #{} (mapcat :declared-by) (:fonts m))
        tokens-declared (into #{} (map (comp name :token)) (fm/declared-tuples (tokens)))]
    (is (seq tokens-declared))
    (is (= tokens-declared attributed)
        (str "declared-but-unattributed: "
             (pr-str (set/difference tokens-declared attributed))
             " / attributed-but-undeclared: "
             (pr-str (set/difference attributed tokens-declared))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Shape — a parse that went wrong must not look like a metric
;; ═══════════════════════════════════════════════════════════════════════════

(deftest compiled-records-carry-a-complete-consistent-metric
  (let [compiled (compiled-records (manifest))]
    (is (seq compiled))
    (doseq [{font-name :name
             :keys [line-height base-line bpp banner metrics-from
                    generator metrics-unavailable]} compiled]
      (testing font-name
        (is (int? line-height))
        (is (int? base-line))
        (is (pos? base-line) "a baseline measured from the bottom of the line is positive")
        (is (< base-line line-height) "the baseline sits inside the line box")
        (is (some? metrics-from) "a metric with no source file is not a measurement")
        (is (nil? metrics-unavailable) "a record with metrics must not also claim it has none")
        (testing "the banner and the descriptor agree"
          (is (some? banner))
          (is (= "lv_font_conv" generator))
          (is (= bpp (:bpp banner)))
          (is (int? (:size banner)))
          (testing "line_height is within a sane band of the declared px size"
            (is (<= (:size banner) line-height (* 2 (:size banner))))))
        (testing "the glyph range is captured as a LIST, so a multi-range banner keeps all of it"
          (is (vector? (:ranges banner)))
          (is (seq (:ranges banner))))))
    (testing "at least one vendored table really does declare more than one range"
      (is (some #(< 1 (count (get-in % [:banner :ranges]))) compiled)
          "a single-string :range would have silently dropped half of it"))))

;; ═══════════════════════════════════════════════════════════════════════════
;; The TTF-probe tuple — emitted, marked, and NOT given a number
;; ═══════════════════════════════════════════════════════════════════════════

(deftest runtime-rasterized-fonts-are-emitted-marked-and-unmeasured
  (let [m (manifest)
        runtime (filterv #(not= "compiled-table" (:resolution %)) (:fonts m))]
    (testing "tokens.edn declares at least one tuple that does NOT reach a compiled table"
      (is (seq runtime)
          (str "no runtime-resolved font in the manifest — if :font-ttf-probe was "
               "removed, this policy is untested and the branch is dead")))
    (doseq [{font-name :name font-symbol :symbol
             :keys [resolution line-height base-line bpp banner
                    asset metrics-from metrics-unavailable]} runtime]
      (testing font-name
        (is (contains? #{"tiny-ttf" "binfont"} resolution))
        (is (nil? font-symbol))
        (is (nil? metrics-from))
        (is (nil? line-height) "a TTF-derived number here would be the exact trap")
        (is (nil? base-line))
        (is (nil? bpp))
        (is (nil? banner))
        (is (string? metrics-unavailable))
        (is (str/includes? metrics-unavailable "controls.wasm")
            "the reason must name what WOULD obtain the metric")
        (is (some? asset))
        (is (.isFile (io/file repo-root asset))
            "the asset the runtime would open must actually exist")))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Refusals — pure, so they need no file and no mutation of the renderer tree
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private synthetic-table
  (str "/****\n * Size: 9 px\n * Bpp: 4\n"
       " * Opts: --font Fake.ttf --bpp 4 --size 9 --range 0x20-0x7E -o fake.c\n ****/\n"
       "static const lv_font_fmt_txt_dsc_t font_dsc = {\n"
       "    .cmap_num = 1,\n    .bpp = 4,\n    .kern_classes = 0,\n"
       "    .bitmap_format = 0,\n};\n"
       "const lv_font_t font_fake_9 = {\n"
       "    .line_height = 11,\n    .base_line = 2,\n"
       "    .underline_position = -1,\n    .underline_thickness = 1,\n};\n"))

(deftest synthetic-table-parses-into-the-expected-shape
  (let [p (fm/parse-table-text synthetic-table "synthetic")]
    (is (= 11 (:line-height p)))
    (is (= 2 (:base-line p)))
    (is (= 4 (:bpp p)))
    (is (= 1 (:cmap-num p)))
    (is (= 0 (:kern-classes p)))
    (is (= -1 (:underline-position p)))
    (is (= 9 (get-in p [:banner :size])))
    (is (= ["0x20-0x7E"] (get-in p [:banner :ranges])))))

(deftest a-table-that-cannot-be-parsed-unambiguously-is-refused
  (testing "two initializers of the same field — the parse would otherwise pick one arbitrarily"
    (let [doubled (str/replace synthetic-table
                               ".line_height = 11,"
                               ".line_height = 11,\n    .line_height = 12,")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ambiguous"
                            (fm/parse-table-text doubled "doubled")))))
  (testing "a required field missing entirely"
    (let [stripped (str/replace synthetic-table ".base_line = 2,\n" "")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no \.base_line initializer"
                            (fm/parse-table-text stripped "stripped")))))
  (testing "a banner whose Bpp disagrees with the descriptor — a hand-edited table"
    (let [edited (str/replace synthetic-table ".bpp = 4," ".bpp = 2,")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"edited away from its generator"
                            (fm/parse-table-text edited "edited"))))))

(deftest a-declared-font-that-resolves-to-nothing-is-refused
  ;; REVERT-TO-BREAK: change classify-resolution's `:else (throw ...)` to
  ;; `:else :tiny-ttf`. This test must FAIL; every other test stays GREEN,
  ;; because no font in the real tree takes that branch.
  (testing "the three real arms classify in renderer.c's own order"
    (is (= :compiled-table (fm/classify-resolution {:name "x_1" :family "x"
                                                    :compiled? true :bin? true :ttf? true})))
    (is (= :binfont (fm/classify-resolution {:name "x_1" :family "x"
                                             :compiled? false :bin? true :ttf? true})))
    (is (= :tiny-ttf (fm/classify-resolution {:name "x_1" :family "x"
                                              :compiled? false :bin? false :ttf? true}))))
  (testing "a tuple reaching none of them would silently draw the fallback face"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"resolves to nothing"
                          (fm/classify-resolution {:name "phantom_bold_9"
                                                   :family "phantom_bold"
                                                   :compiled? false
                                                   :bin? false
                                                   :ttf? false}))))
  (testing "and the whole manifest refuses such a tokens.edn rather than emitting it"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"resolves to nothing"
         (fm/font-metrics {:repo-root repo-root
                           :tokens {:fonts {:phantom-bold-9 {:family "phantom_bold"
                                                             :size 9}}}})))))

(deftest a-malformed-tokens-font-tuple-is-refused
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not \{:family"
                        (fm/declared-tuples {:fonts {:bad {:family "x"}}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not \{:family"
                        (fm/declared-tuples {:fonts {:bad {:family 12 :size 12}}}))))

(deftest a-missing-renderer-c-fails-loudly-rather-than-emitting-an-empty-set
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"renderer\.c not found"
                        (fm/resolve-font-arms "no/such/repo/root"))))

(deftest the-emitter-reads-the-resolution-order-from-renderer-c
  (let [{:keys [arms fallback-symbol]} (fm/resolve-font-arms repo-root)]
    (is (seq arms))
    (is (string? fallback-symbol))
    (is (contains? (set (map :symbol arms)) fallback-symbol)
        "the fallback face must itself be one of the string-matched compiled fonts")
    (doseq [{font-name :name font-symbol :symbol} arms]
      (is (str/includes? font-symbol font-name)
          (str "arm \"" font-name "\" returns &" font-symbol
               " — a name/symbol mismatch means the parse paired the wrong lines")))))
