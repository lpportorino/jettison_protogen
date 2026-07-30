(ns lvgl-codegen.font-metrics
  "Font metrics read from the COMPILED tables — the only artifact that renders.

   ═══ WHY THIS NAMESPACE READS C AND NEVER A TTF ═══

   `resolve_font` (renderer/src/renderer.c) is ORDERED: a `strcmp` against the
   compiled-in `lv_font_t` symbols first, then `P:fonts/<name>.bin`, then
   TinyTTF over `P:fonts/<family>.ttf`. Every name the strcmp arm answers
   returns a C-array bitmap table — `renderer/src/font_*.c` cut by
   `lv_font_conv` at `--bpp 4` over a restricted `--range`
   (tools/gen_fonts.sh), or a vendored `renderer/lvgl/src/font/lv_font_*.c`.
   Those tables carry their OWN `line_height` / `base_line` and they DO NOT
   agree with the TTF they were cut from.

   Measured in this tree (compiled vs. what lv_tiny_ttf would derive from the
   same face at the same px size — that arithmetic is reproduced in
   `lvgl-codegen.font-metrics-test`, deliberately not here):

     * six of the eight custom faces disagree on `line_height`, in BOTH
       directions — so no additive or multiplicative correction rescues a
       TTF-derived number;
     * `base_line` agrees for five of the eight, which is exactly why a
       TTF-derived emitter FAILS QUIETLY: spot-check the wrong field and it
       looks right;
     * but the (`line_height`, `base_line`) PAIR differs for every one of the
       eight, which is what makes the test's discriminator total.

   So: **this namespace contains no TTF reader and must never grow one.** It
   parses C. The TTF arithmetic lives only in the test, where its job is to
   prove the emitted numbers are NOT that.

   ═══ WHAT IT COVERS ═══

   The emitted set is the UNION of

     * every name `resolve_font`'s strcmp arm answers — parsed out of
       renderer.c, never mirrored here, so a font added there whose table
       cannot be found fails this generator loudly; and
     * every `:fonts` tuple declared in `edn/tokens.edn`, whose C name is
       `\"<family>_<size>\"`.

   A tuple the strcmp arm does not name falls through to `.bin` and then to
   TinyTTF. Such a font IS drawable and IS emitted — as a record whose metrics
   are `nil` and whose `:metrics-unavailable` says why and how to obtain them.
   Emitting nothing for it would report \"I could not look\" and \"there is no
   such font\" as the same absence; throwing would red a tree that is correct
   by design (`:font-ttf-probe` exists to exercise that arm, and
   `renderer/wasm_harness/tests/visual_regression.rs`
   `test_wasi_ttf_font_renders_at_uncompiled_size` asserts it renders). What is
   refused, loudly, is a declared tuple that reaches NEITHER a table, NOR a
   `.bin`, NOR a `.ttf` — `resolve_font` logs and returns the fallback face, so
   the token would name one font and draw another.

   ═══ ENTRY POINTS ═══

     (font-metrics {:repo-root \"../..\" :tokens <tokens map>})  ; data
     clojure -M -m lvgl-codegen.font-metrics [--repo-root D] [--tokens F]
                                             [--output F]

   `--output` is optional: with no destination the manifest goes to stdout.
   No renderer.mk lane consumes a committed artifact from this generator yet,
   so it does not invent one."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m])
  (:import (java.io File)
           (java.util.regex Pattern)))

(set! *warn-on-reflection* true)

(def repo-root-default
  "Repo root relative to the tool root (tools/renderer-gen), which is the CWD
   every renderer.mk lane and the kaocha suite run from."
  "../..")

(def tokens-default "edn/tokens.edn")

(def renderer-c-path
  "The one home of the resolution order. Parsed, never mirrored."
  "renderer/src/renderer.c")

(def table-search-dirs
  "Where a compiled `lv_font_t` table may live: this repo's own lv_font_conv
   output, and the vendored LVGL font tree (the montserrat fallbacks
   `resolve_font` also names)."
  ["renderer/src" "renderer/lvgl/src/font"])

(def asset-font-dir
  "The WASI preopen root is `renderer/assets` (the harness's `wasi_root`), and
   LV_FS_POSIX_LETTER is 'P' with an empty LV_FS_POSIX_PATH — so `P:fonts/x`
   is this directory's `x`."
  "renderer/assets/fonts")

(def ^:private resolve-font-open
  "The exact signature line that opens the resolution function. A rename here
   fails generation rather than silently emitting an empty font set."
  "static const lv_font_t *resolve_font(const char *name) {")

(defn- at
  "Repo-root-relative File."
  ^File [repo-root & parts]
  (reduce (fn [^File acc p] (io/file acc (str p))) (io/file (str repo-root)) parts))

;; ── renderer.c: the resolution order ────────────────────────────────────────

(defn- resolve-font-body
  "The text of `resolve_font`'s body: from its signature to the first
   column-0 closing brace."
  [repo-root]
  (let [f (at repo-root renderer-c-path)]
    (when-not (.isFile f)
      (throw (ex-info "renderer.c not found — cannot derive the font resolution order"
                      {:path (.getPath f) :repo-root (str repo-root)})))
    (let [txt (slurp f)
          start (str/index-of txt resolve-font-open)]
      (when-not start
        (throw (ex-info (str "resolve_font signature not found in renderer.c — the "
                             "resolution order moved and this generator must be "
                             "re-pointed, not silently emptied")
                        {:path (.getPath f) :expected resolve-font-open})))
      (let [end (str/index-of txt "\n}" start)]
        (when-not end
          (throw (ex-info "resolve_font body has no column-0 closing brace"
                          {:path (.getPath f)})))
        (subs txt start end)))))

(def ^:private strcmp-arm-re
  #"strcmp\(name,\s*\"([^\"]+)\"\)\s*==\s*0\)\s*return\s*&\s*([A-Za-z_][A-Za-z0-9_]*)\s*;")

(def ^:private fallback-re
  #"LOG_ERROR\([^;]*\);\s*return\s*&\s*([A-Za-z_][A-Za-z0-9_]*)\s*;")

(defn resolve-font-arms
  "Parse `resolve_font`: the ordered name→symbol strcmp arms, plus the symbol
   the not-found path falls back to.

   Both are REQUIRED. An empty arm list or a missing fallback means the
   function was restructured, and an emitter that shrugged at that would emit
   a manifest describing a resolution order the renderer no longer has."
  [repo-root]
  (let [body (resolve-font-body repo-root)
        arms (mapv (fn [[_ font-name sym]] {:name font-name :symbol sym})
                   (re-seq strcmp-arm-re body))
        dup (->> arms (map :name) frequencies (keep (fn [[k n]] (when (< 1 n) k))) sort vec)
        fallback (second (re-find fallback-re body))]
    (when (empty? arms)
      (throw (ex-info "resolve_font names no compiled font — parse produced zero strcmp arms"
                      {:pattern (str strcmp-arm-re)})))
    (when (seq dup)
      (throw (ex-info (str "resolve_font matches the same font name more than once: "
                           (str/join ", " dup) " — the later arm is dead code")
                      {:duplicates dup})))
    (when-not fallback
      (throw (ex-info (str "resolve_font has no LOG_ERROR fallback return — the "
                           "not-found path changed shape")
                      {:pattern (str fallback-re)})))
    {:arms arms :fallback-symbol fallback}))

;; ── the compiled C table ────────────────────────────────────────────────────

(defn- defines-symbol?
  "True when `txt` contains a top-level `lv_font_t <sym> = {` DEFINITION
   (the public table object), not merely a declaration or a reference."
  [txt sym]
  (boolean (re-find (re-pattern (str "lv_font_t\\s+" (Pattern/quote (str sym)) "\\s*=\\s*\\{"))
                    txt)))

(defn- dir-font-sources
  "Every `*font*.c` in one search dir (empty when the dir is absent)."
  [repo-root dir]
  (let [^File d (at repo-root dir)]
    (filterv (fn [^File f]
               (and (.isFile f)
                    (str/ends-with? (.getName f) ".c")
                    (str/includes? (.getName f) "font")))
             (or (seq (.listFiles d)) []))))

(defn- table-file
  "The file defining the public `lv_font_t <sym>` object.

   Fast path: `<dir>/<sym>.c`, VERIFIED to define the symbol rather than
   trusted for its name. Slow path: scan the search dirs. Absent from both is
   a throw — a name `resolve_font` answers whose table cannot be found means
   the emitted set is missing a font that renders."
  ^File [repo-root sym]
  (or (first (for [d table-search-dirs
                   :let [f (at repo-root d (str sym ".c"))]
                   :when (and (.isFile f) (defines-symbol? (slurp f) sym))]
               f))
      (first (for [d table-search-dirs
                   ^File f (dir-font-sources repo-root d)
                   :when (defines-symbol? (slurp f) sym)]
               f))
      (throw (ex-info (str "no compiled table defines lv_font_t " sym
                           " — resolve_font names it but nothing in "
                           (str/join " / " table-search-dirs) " provides it")
                      {:symbol sym :searched (vec table-search-dirs)}))))

(defn- one-int
  "The single `.<field> = <int>` initializer in a table, or nil when the field
   is optional and absent.

   Exactly-once is enforced: two initializers of the same field mean the parse
   picked one arbitrarily, and an arbitrary metric is worse than no metric."
  [txt field {:keys [required? label]}]
  (let [hits (re-seq (re-pattern (str "\\." (Pattern/quote (str field)) "\\s*=\\s*(-?\\d+)")) txt)]
    (case (count hits)
      1 (parse-long (second (first hits)))
      0 (when required?
          (throw (ex-info (str "compiled font table has no ." field " initializer")
                          {:field field :table label})))
      (throw (ex-info (str "compiled font table has " (count hits) " ." field
                           " initializers — ambiguous, refusing to guess")
                      {:field field :table label :found (count hits)})))))

(def ^:private banner-scan-chars
  "The generator banner is the file's first block comment; scanning past it
   risks matching a stray `Opts:` inside the glyph data."
  4096)

(defn parse-banner
  "The lv_font_conv banner (`Size:` / `Bpp:` / `Opts:`), or nil when the table
   carries none. `:ranges` collects every `--range`/`-r` value in order — the
   vendored montserrat tables pass two (a Montserrat range and a FontAwesome
   one), so a single string would have silently dropped half."
  [txt]
  (let [head (subs txt 0 (min banner-scan-chars (count txt)))
        size (some-> (re-find #"(?m)^\s*\*\s*Size:\s*(\d+)\s*px" head) second parse-long)
        bpp (some-> (re-find #"(?m)^\s*\*\s*Bpp:\s*(\d+)" head) second parse-long)
        opts (some-> (re-find #"(?m)^\s*\*\s*Opts:[ \t]*(.*?)[ \t]*$" head) second)]
    (when (or size bpp opts)
      {:size size
       :bpp bpp
       :opts opts
       :ranges (mapv second (re-seq #"(?:^|\s)(?:--range|-r)\s+(\S+)" (or opts "")))})))

(defn parse-table-text
  "Metrics out of one compiled table's SOURCE TEXT. Pure — `label` only reaches
   error messages, so the test can drive every refusal without a file."
  [txt label]
  (let [banner (parse-banner txt)
        req {:required? true :label label}
        bpp (one-int txt "bpp" req)]
    (when (and (:bpp banner) (not= (:bpp banner) bpp))
      (throw (ex-info (str "compiled font table's banner says Bpp " (:bpp banner)
                           " but its descriptor says .bpp " bpp
                           " — the table was edited away from its generator")
                      {:table label :banner-bpp (:bpp banner) :descriptor-bpp bpp})))
    {:line-height (one-int txt "line_height" req)
     :base-line (one-int txt "base_line" req)
     :underline-position (one-int txt "underline_position" {:label label})
     :underline-thickness (one-int txt "underline_thickness" {:label label})
     :bpp bpp
     :cmap-num (one-int txt "cmap_num" req)
     :kern-classes (one-int txt "kern_classes" req)
     :banner banner}))

;; ── resolution classification ───────────────────────────────────────────────

(defn classify-resolution
  "Which arm of `resolve_font` a font NAME lands on, given what exists.

   Pure, and total by construction: the ordering mirrors renderer.c (compiled
   strcmp → `P:fonts/<name>.bin` → TinyTTF `P:fonts/<family>.ttf`), and the
   fourth case is a THROW rather than a fourth keyword. A declared tuple
   reaching none of the three does not render as itself — `resolve_font` logs
   and returns the fallback face — so the token names one font and draws
   another, which is a defect in the tokens, not a shape this manifest carries."
  [{font-name :name :keys [family compiled? bin? ttf?]}]
  (cond compiled? :compiled-table
        bin? :binfont
        ttf? :tiny-ttf
        :else
        (throw (ex-info (str "declared font \"" font-name "\" resolves to nothing: no compiled "
                             "lv_font_t, no " asset-font-dir "/" font-name ".bin, no "
                             asset-font-dir "/" family ".ttf — resolve_font would "
                             "silently draw the fallback face instead")
                        {:font font-name :family family :asset-dir asset-font-dir}))))

(defn- unmeasured-reason
  "Why a drawable font has no metrics here, and what WOULD obtain them. A
   record with null metrics and no reason would be indistinguishable from a
   parse that failed silently."
  [resolution asset]
  (case resolution
    :tiny-ttf
    (str "rasterized at runtime by lv_tiny_ttf from P:fonts/" asset
         "; no compiled table exists to read. line_height/base_line are derived"
         " inside the wasm by lv_tiny_ttf.c from stbtt_GetFontVMetrics scaled by"
         " stbtt_ScaleForMappingEmToPixels and TRUNCATED — so the only measurement"
         " that records its rasterizer is one taken from a running controls.wasm,"
         " never one computed from the TTF here.")
    :binfont
    (str "loaded at runtime by lv_binfont_create from P:fonts/" asset
         "; the metrics live in that binary blob's header, not in any C table.")
    nil))

;; ── tuple declaration ───────────────────────────────────────────────────────

(defn declared-tuples
  "The `:fonts` tuples of tokens.edn as {:token :family :size :name}. `:name`
   is the string `lvgl-codegen.resolve/resolve-font` hands the renderer."
  [tokens]
  (->> (:fonts tokens)
       (mapv (fn [[token {:keys [family size]}]]
               (when-not (and (string? family) (int? size))
                 (throw (ex-info (str "font tuple " token
                                      " is not {:family <string> :size <int>}")
                                 {:token token :tuple (get-in tokens [:fonts token])})))
               {:token token :family family :size size :name (str family "_" size)}))
       (sort-by :name)
       vec))

;; ── the manifest ────────────────────────────────────────────────────────────

(defn- strip-root
  "`../../renderer/src/x.c` → `renderer/src/x.c`, so provenance is quotable."
  [repo-root ^File f]
  (str/replace (.getPath f)
               (re-pattern (str "^" (Pattern/quote (str repo-root)) "/?"))
               ""))

(defn font-metrics
  "The full font-metrics manifest as data.

   `:repo-root` locates renderer.c, the table dirs and the asset dir;
   `:tokens` is the PARSED tokens map (not a path) so a caller — the test
   included — can drive declaration cases without editing edn/tokens.edn."
  [{:keys [repo-root tokens]}]
  (let [{:keys [arms fallback-symbol]} (resolve-font-arms repo-root)
        symbol-of (into {} (map (juxt :name :symbol)) arms)
        name-of-symbol (into {} (map (juxt :symbol :name)) arms)
        compiled-names (set (keys symbol-of))
        fallback-name (get name-of-symbol fallback-symbol)
        tuples (declared-tuples tokens)
        declared-by (reduce (fn [acc {:keys [token] font-name :name}]
                              (update acc font-name (fnil conj []) token))
                            {}
                            tuples)
        family-by-name (into {} (map (juxt :name :family)) tuples)
        family-of (fn [font-name]
                    (or (family-by-name font-name)
                        (let [i (str/last-index-of font-name "_")]
                          (when (and i (pos? i)) (subs font-name 0 i)))))
        record
        (fn [font-name]
          (let [family (family-of font-name)
                bin (at repo-root asset-font-dir (str font-name ".bin"))
                ttf (when family (at repo-root asset-font-dir (str family ".ttf")))
                resolution (classify-resolution
                            {:name font-name
                             :family family
                             :compiled? (contains? compiled-names font-name)
                             :bin? (.isFile bin)
                             :ttf? (boolean (and ttf (.isFile ttf)))})
                sym (when (= :compiled-table resolution) (symbol-of font-name))
                ^File file (when sym (table-file repo-root sym))
                parsed (when file (parse-table-text (slurp file) (strip-root repo-root file)))
                asset (case resolution
                        :binfont (str font-name ".bin")
                        :tiny-ttf (str family ".ttf")
                        nil)]
            (merge {:name font-name
                    :family family
                    :symbol sym
                    :resolution (name resolution)
                    :metrics-from (when file (strip-root repo-root file))
                    :generator nil
                    :line-height nil
                    :base-line nil
                    :underline-position nil
                    :underline-thickness nil
                    :bpp nil
                    :cmap-num nil
                    :kern-classes nil
                    :banner nil
                    :asset (when asset (str asset-font-dir "/" asset))
                    :metrics-unavailable (unmeasured-reason resolution asset)
                    :declared-by (vec (sort (map name (get declared-by font-name))))
                    :renderer-fallback (= font-name fallback-name)}
                   parsed
                   (when (:banner parsed) {:generator "lv_font_conv"}))))]
    {:schema-version 1
     :kind "lvgl-compiled-font-metrics"
     :source-method "compiled-c-table-parse"
     :scope ["lv_font_t tables reachable by name from resolve_font"
             "font tuples declared in tokens.edn :fonts"]
     :excluded ["any metric derived from renderer/assets/fonts/*.ttf"
                "runtime-rasterized (TinyTTF) metrics — emitted with null metrics + a reason"
                "runtime .bin font metrics — emitted with null metrics + a reason"]
     :resolution-order ["compiled-table" "binfont" "tiny-ttf"]
     :renderer-fallback fallback-name
     :fonts (mapv record (sort (into compiled-names (map :name) tuples)))}))

;; ── JSON projection + CLI ───────────────────────────────────────────────────

(defn- jsonable
  "Keyword keys and keyword values → their names, recursively."
  [x]
  (cond (map? x) (into (sorted-map)
                       (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) (jsonable v)]))
                       x)
        (sequential? x) (mapv jsonable x)
        (keyword? x) (name x)
        :else x))

(defn- parse-args
  "Parse `--flag value` CLI pairs into {:repo-root :tokens :output}, defaulting
   :repo-root/:tokens when their flags are absent. Throws on an odd arg count
   or an unrecognized flag rather than silently dropping either."
  [args]
  (when (odd? (count args))
    (throw (ex-info "flags must come in --flag value pairs" {:args (vec args)})))
  (reduce (fn [acc [flag value]]
            (case flag
              "--repo-root" (assoc acc :repo-root value)
              "--tokens" (assoc acc :tokens value)
              "--output" (assoc acc :output value)
              (throw (ex-info (str "unknown flag: " flag)
                              {:flag flag
                               :expected #{"--repo-root" "--tokens" "--output"}}))))
          {:repo-root repo-root-default :tokens tokens-default}
          (partition 2 args)))

(defn -main
  "Emit the compiled-font-metrics manifest. `--output` optional (stdout)."
  [& args]
  (let [{:keys [repo-root tokens output]} (parse-args args)
        manifest (jsonable (font-metrics {:repo-root repo-root
                                          :tokens (edn/read-string (slurp tokens))}))]
    (if output
      (do (io/make-parents output)
          (with-open [w (io/writer output)]
            (json/write manifest w :indent true :escape-slash false)
            (.write w "\n"))
          (println (str "font-metrics: " (count (get manifest "fonts"))
                        " fonts (compiled tables read; no TTF consulted) -> " output)))
      (do (json/write manifest *out* :indent true :escape-slash false)
          (println)))))

;; ── function schemas ────────────────────────────────────────────────────────

(def ^:private font-record-schema
  [:map
   [:name [:string {:min 1}]]
   [:family [:maybe [:string {:min 1}]]]
   [:symbol [:maybe [:string {:min 1}]]]
   [:resolution [:enum "compiled-table" "binfont" "tiny-ttf"]]
   [:line-height [:maybe :int]]
   [:base-line [:maybe :int]]
   [:declared-by [:vector [:string {:min 1}]]]
   [:renderer-fallback :boolean]])

(m/=> resolve-font-arms
      [:=> [:cat some?]
       [:map
        [:arms [:vector [:map [:name [:string {:min 1}]] [:symbol [:string {:min 1}]]]]]
        [:fallback-symbol [:string {:min 1}]]]])

(m/=> parse-banner
      [:=> [:cat string?]
       [:maybe [:map [:size [:maybe :int]] [:bpp [:maybe :int]]
                [:opts [:maybe string?]] [:ranges [:vector string?]]]]])

(m/=> parse-table-text
      [:=> [:cat string? some?] [:map [:line-height :int] [:base-line :int]]])

(m/=> classify-resolution
      [:=> [:cat [:map [:name string?]]] [:enum :compiled-table :binfont :tiny-ttf]])

(m/=> declared-tuples
      [:=> [:cat [:map-of :keyword some?]]
       [:vector [:map [:token :keyword] [:family [:string {:min 1}]] [:size :int]
                 [:name [:string {:min 1}]]]]])

(m/=> font-metrics
      [:=> [:cat [:map [:tokens [:map-of :keyword some?]]]]
       [:map [:fonts [:vector font-record-schema]]]])
