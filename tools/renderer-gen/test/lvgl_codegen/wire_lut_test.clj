(ns lvgl-codegen.wire-lut-test
  "The WIRE-NUMBER LEG: does an authoring keyword still mean the LVGL constant
   it is named after?

   WHAT IS ALREADY COVERED, so that this suite's own claim stays honest.
   `renderer.mk`'s `construct-bindings` re-extracts the LVGL enum facts with
   clang + a compiled probe and BYTE-compares the result against both committed
   projections (`generated/enums.clj` and `renderer/generated/ui_luts.h`). A
   hand-edit of either is already a red there. What that lane cannot see is the
   leg UPSTREAM of it: the hand-written authoring-keyword maps in
   `lvgl-codegen.emit-proto`, which no extraction produces and no byte
   comparison reaches. `emit-proto/layout-flow-keyword->member` is the live
   instance — `:layout` keywords hand-paired to FlexFlow proto members, where a
   swapped pair is legal proto, compiles, regenerates identically, and renders
   the wrong flow. `renderer/coverage_matrix/run.sh`'s `FLEX_KWS`/`FLEX_VALS`
   is a second, in a shell script; `emit-proto`'s grid-track constants are a
   third, hand-carried from `lv_grid.h`. `lvgl-codegen.expand`'s
   `layout-directives` + `extract-layout` are a FOURTH, one level further
   upstream still: the class TOKEN that selects the `:layout` keyword the other
   three then carry. Same defect class and the same invisibility - point
   \"flex-col\" at a different valid flow and every stage below agrees with
   itself all the way to the framebuffer.

   WHAT THIS SUITE ADDS.
   1. An INDEPENDENT oracle for the header values. `construct-bindings` and the
      committed bindings share one extractor; a defect in it agrees with
      itself. This namespace re-reads `renderer/lvgl/**.h` in pure Clojure and
      resolves each enumerator's value from the header text — a second opinion,
      not a second copy. The CHECK needs no clang, no gcc, no jq and no
      network, so it is available wherever `clojure -M:test` is, including a
      checkout where the extraction cannot run at all. (Its LANE,
      `renderer.mk`'s `clj-schema-test`, does declare `construct-bindings` as a
      prerequisite, so the lane is not toolchain-free even though this suite
      is.)
   2. The composed question the byte comparison never asks:
      authoring keyword -> proto wire number -> (LUT | direct cast) -> LVGL
      value, against the value the vendored header declares for the constant
      that keyword is NAMED after.
   3. TOTALITY in both directions. Every keyword in every generated map, and
      every non-sentinel enumerator in every typedef the factory requires, is
      judged; a keyword with no enumerator and an enumerator with no keyword are
      both FAILURES, never skips.

   WHY THE ORACLE MAY NOT SKIP. Every parse step here refuses rather than
   shrugs: a typedef found in zero or several headers, a preprocessor directive
   whose form is not handled, an identifier that resolves nowhere, a token the
   expression grammar does not know. A silent skip would make this suite report
   `clean` and `I could not look` as the same green.

   WHAT IT DOES NOT SEE. The NAME derivation (C constant -> authoring keyword)
   is the factory's own (`lift/enum-edn->constructs`), used on both sides on
   purpose: re-implementing it here would be the second silently-diverging
   source these gates exist to prevent. So this suite judges VALUES, which is
   the wrong-but-legal class; it cannot catch a defect in the kebab-casing
   itself. And it reads the vendored tree only — nothing here proves the
   renderer was BUILT from that tree."
  (:require [camel-snake-kebab.core :as csk]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lvgl-codegen.construct.factory :as factory]
            [lvgl-codegen.construct.lift :as lift]
            [lvgl-codegen.emit-proto :as emit-proto]
            [lvgl-codegen.expand :as expand]
            [lvgl-codegen.schema :as schema]
            [lvgl-codegen.style-props :as style-props]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

;; Paths are CWD-relative to tools/renderer-gen — the directory `renderer.mk`'s
;; `clj-schema-test` cd's into, and the same convention theme_style_groups_test
;; uses.
(def ^:private lvgl-src-dir "../../renderer/lvgl/src")

(def ^:private lv-conf-path "../../renderer/lv_conf.h")

(def ^:private luts-header-path "../../renderer/generated/ui_luts.h")

(def ^:private coverage-matrix-path "../../renderer/coverage_matrix/run.sh")

;; ── The header oracle ───────────────────────────────────────────────────────
;; A pure-Clojure read of the vendored LVGL headers. Deliberately NOT the
;; production extractor: that one is clang + a compiled probe, and reusing it
;; would make this suite agree with itself by construction.

(defn- strip-comments
  "C source with `/*…*/` and `//…` removed, string and char literals left
   intact. Newlines inside block comments are preserved so line-oriented
   preprocessor handling downstream still sees the right structure."
  [^String src]
  (let [n (.length src)
        out (StringBuilder. n)]
    (loop [i 0
           mode :code]
      (if (>= i n)
        (.toString out)
        (let [c (.charAt src i)
              c2 (when (< (inc i) n) (.charAt src (inc i)))]
          (case mode
            :code (cond (and (= c \/) (= c2 \*)) (recur (+ i 2) :block)
                        (and (= c \/) (= c2 \/)) (recur (+ i 2) :line)
                        (= c \") (do (.append out c) (recur (inc i) :string))
                        (= c \') (do (.append out c) (recur (inc i) :char))
                        :else (do (.append out c) (recur (inc i) :code)))
            :block (cond (and (= c \*) (= c2 \/)) (recur (+ i 2) :code)
                         (= c \newline) (do (.append out c) (recur (inc i) :block))
                         :else (recur (inc i) :block))
            :line (if (= c \newline)
                    (do (.append out c) (recur (inc i) :code))
                    (recur (inc i) :line))
            :string (do (.append out c)
                        (cond (= c \\) (do (.append out c2) (recur (+ i 2) :string))
                              (= c \") (recur (inc i) :code)
                              :else (recur (inc i) :string)))
            :char (do (.append out c)
                      (cond (= c \\) (do (.append out c2) (recur (+ i 2) :char))
                            (= c \') (recur (inc i) :code)
                            :else (recur (inc i) :char)))))))))

(def ^:private headers
  "Every vendored LVGL header, comment-stripped, as `[path text]` — read once."
  (delay (let [root (io/file lvgl-src-dir)]
           (when-not (.isDirectory root)
             (throw (ex-info "vendored LVGL source tree not found"
                             {:path lvgl-src-dir :cwd (System/getProperty "user.dir")})))
           (into []
                 (comp (filter #(.isFile ^java.io.File %))
                       (filter #(str/ends-with? (.getName ^java.io.File %) ".h"))
                       (map (fn [^java.io.File f]
                              [(.getPath f) (strip-comments (slurp f))])))
                 (file-seq root)))))

(def ^:private object-macros
  "Object-like `#define NAME <expr>` bodies harvested from the vendored
   headers, as NAME -> the SET of distinct bodies seen. A set rather than a
   value because LVGL redefines plenty of names under `#ifdef` guards; a lookup
   that finds more than one body REFUSES instead of picking, so an ambiguity
   can never be resolved silently in the quiet direction."
  (delay (reduce (fn [acc [_ text]]
                   (reduce (fn [a [_ nm body]]
                             (update a nm (fnil conj #{}) (str/trim body)))
                           acc
                           (re-seq #"(?m)^[ \t]*#[ \t]*define[ \t]+([A-Za-z_]\w*)[ \t]+(\S.*)$"
                                   text)))
                 {}
                 @headers)))

(def ^:private lv-conf-macros
  "Object-like `#define`s from the RENDERER's own `lv_conf.h` — the build's
   actual configuration, and the only authority consulted for a `#if` guard
   inside an enum body. A guard naming something absent here is a refusal, not
   a default. Same set-valued shape as `object-macros` for the same reason: a
   name defined twice must REFUSE, never silently take the last one."
  (delay (reduce (fn [acc [_ nm body]] (update acc nm (fnil conj #{}) (str/trim body)))
                 {}
                 (re-seq #"(?m)^[ \t]*#[ \t]*define[ \t]+([A-Za-z_]\w*)[ \t]+(\S.*)$"
                         (strip-comments (slurp lv-conf-path))))))

;; ── A very small C constant-expression evaluator ────────────────────────────
;; Only the forms the vendored enum bodies actually use. Every unhandled token,
;; operator or identifier throws with the offending text.

(defn- tokenize
  "C constant expression -> tokens (`:num`/`:id`/operator strings). Throws
   naming the character on anything the grammar does not know."
  [^String s]
  (loop [i 0
         acc []]
    (cond
      (>= i (.length s)) acc
      (Character/isWhitespace (.charAt s i)) (recur (inc i) acc)
      :else
      ;; Every pattern below is capture-group free on purpose: `re-find` then
      ;; returns the matched STRING, never a vector, so `(count m)` is the
      ;; consumed length.
      (let [rest-s (subs s i)]
        (if-let [m (re-find #"^(?:0[xX][0-9a-fA-F]+|[0-9]+)[uUlL]*" rest-s)]
          (let [digits (str/replace m #"[uUlL]+$" "")
                v (if (str/starts-with? (str/lower-case digits) "0x")
                    (Long/parseLong (subs digits 2) 16)
                    (Long/parseLong digits))]
            (recur (+ i (count m)) (conj acc [:num v])))
          (if-let [m (re-find #"^[A-Za-z_]\w*" rest-s)]
            (recur (+ i (count m)) (conj acc [:id m]))
            (if-let [m (re-find #"^(?:<<|>>|\||\^|&|~|\+|-|\*|/|\(|\))" rest-s)]
              (recur (+ i (count m)) (conj acc [:op m]))
              (throw (ex-info "unhandled character in C constant expression"
                              {:expression s :at i :char (.charAt s i)})))))))))

(declare ^:private parse-bitor)

(defn- parse-primary
  [tokens resolve-id]
  (let [[[kind v] & more] tokens]
    (case kind
      :num [v more]
      :id [(resolve-id v) more]
      :op (cond
            (= v "(") (let [[value after] (parse-bitor more resolve-id)]
                        (when-not (= [:op ")"] (first after))
                          (throw (ex-info "unbalanced parenthesis in C constant expression"
                                          {:tokens tokens})))
                        [value (rest after)])
            (contains? #{"-" "~" "+"} v) (let [[value after] (parse-primary more resolve-id)]
                                           [(case v
                                              "-" (- value)
                                              "~" (bit-not value)
                                              value)
                                            after])
            :else (throw (ex-info "unexpected operator in C constant expression"
                                  {:operator v :tokens tokens})))
      (throw (ex-info "unexpected end of C constant expression" {:tokens tokens})))))

(defn- parse-binary
  "Left-associative level over `ops` (operator string -> fn), delegating to
   `next-level`."
  [tokens resolve-id ops next-level]
  (loop [[value more] (next-level tokens resolve-id)]
    (let [[kind op] (first more)]
      (if (and (= :op kind) (contains? ops op))
        (let [[rhs after] (next-level (rest more) resolve-id)]
          (recur [((ops op) value rhs) after]))
        [value more]))))

(defn- parse-mul [tokens resolve-id]
  (parse-binary tokens resolve-id {"*" * "/" quot} parse-primary))

(defn- parse-add [tokens resolve-id]
  (parse-binary tokens resolve-id {"+" + "-" -} parse-mul))

(defn- parse-shift [tokens resolve-id]
  (parse-binary tokens resolve-id {"<<" bit-shift-left ">>" bit-shift-right} parse-add))

(defn- parse-bitand [tokens resolve-id]
  (parse-binary tokens resolve-id {"&" bit-and} parse-shift))

(defn- parse-bitxor [tokens resolve-id]
  (parse-binary tokens resolve-id {"^" bit-xor} parse-bitand))

(defn- parse-bitor [tokens resolve-id]
  (parse-binary tokens resolve-id {"|" bit-or} parse-bitxor))

(defn- eval-c-expr
  "Evaluate a C constant expression, resolving identifiers through
   `resolve-id`. Throws on leftover tokens — a partially consumed expression
   would otherwise yield a plausible wrong number."
  [expr resolve-id]
  (let [tokens (tokenize expr)
        _ (when (empty? tokens)
            (throw (ex-info "empty C constant expression" {:expression expr})))
        [value more] (parse-bitor tokens resolve-id)]
    (when (seq more)
      (throw (ex-info "trailing tokens in C constant expression"
                      {:expression expr :remaining more})))
    value))

(defn- macro-value
  "Resolve an object-like macro to an integer, recursively. Refuses an unknown
   name and refuses an AMBIGUOUS one (several distinct bodies across the tree)."
  [nm depth]
  (when (> depth 16)
    (throw (ex-info "macro expansion too deep (cycle?)" {:macro nm})))
  (let [bodies (get @object-macros nm)]
    (cond
      (nil? bodies) (throw (ex-info "unresolvable identifier in LVGL enum body"
                                    {:identifier nm}))
      (not= 1 (count bodies)) (throw (ex-info "ambiguous macro: several distinct bodies"
                                              {:macro nm :bodies bodies}))
      :else (eval-c-expr (first bodies) #(macro-value % (inc depth))))))

;; ── Enum block extraction ───────────────────────────────────────────────────

(defn- conf-flag-true?
  "Is `nm` defined to a non-zero value in the renderer's own `lv_conf.h`?
   Refuses a name absent there rather than assuming a default, and refuses an
   ambiguous one rather than picking."
  [nm]
  (let [bodies (get @lv-conf-macros nm)]
    (cond
      (nil? bodies) (throw (ex-info "enum body is guarded by a macro absent from lv_conf.h"
                                    {:macro nm :conf lv-conf-path}))
      (not= 1 (count bodies)) (throw (ex-info "lv_conf.h defines this guard more than once"
                                              {:macro nm :bodies bodies}))
      :else (not (zero? (eval-c-expr (first bodies) #(macro-value % 0)))))))

(defn- apply-preprocessor
  "Resolve `#if <IDENT>` / `#endif` inside an enum body against `lv_conf.h`,
   dropping the members of a false branch. Every other directive form
   (`#else`, `#elif`, `#ifdef`, `#if` with an expression) REFUSES — this parser
   is allowed to be incomplete, never to guess."
  [body]
  (loop [lines (str/split-lines body)
         stack []
         out []]
    (if-let [line (first lines)]
      (let [trimmed (str/trim line)]
        (cond
          (not (str/starts-with? trimmed "#"))
          (recur (rest lines) stack (if (every? true? stack) (conj out line) out))

          (re-matches #"#\s*if\s+[A-Za-z_]\w*" trimmed)
          (recur (rest lines)
                 (conj stack (conf-flag-true? (re-find #"[A-Za-z_]\w*$" trimmed)))
                 out)

          (re-matches #"#\s*endif.*" trimmed)
          (do (when (empty? stack)
                (throw (ex-info "#endif without #if in enum body" {:body body})))
              (recur (rest lines) (pop stack) out))

          :else (throw (ex-info "unhandled preprocessor directive inside an enum body"
                                {:directive trimmed}))))
      (do (when (seq stack)
            (throw (ex-info "unterminated #if in enum body" {:body body})))
          (str/join "\n" out)))))

(defn- split-top-level
  "Split an enum body on commas at paren depth 0."
  [body]
  (loop [remaining (seq body)
         depth 0
         cur (StringBuilder.)
         out []]
    (if-let [c (first remaining)]
      (cond
        (= c \() (recur (rest remaining) (inc depth) (.append cur c) out)
        (= c \)) (recur (rest remaining) (dec depth) (.append cur c) out)
        (and (= c \,) (zero? depth)) (recur (rest remaining) depth (StringBuilder.)
                                            (conj out (.toString cur)))
        :else (recur (rest remaining) depth (.append cur c) out))
      (conj out (.toString cur)))))

(defn- parse-enum-body
  "Enum body text -> ordered `[{:name … :value …}]`, C semantics: an
   enumerator with no initialiser is its predecessor plus one."
  [body]
  (let [items (->> (split-top-level (apply-preprocessor body))
                   (map str/trim)
                   (remove str/blank?))]
    (first
     (reduce
      (fn [[acc prev] item]
        (let [[_ nm expr] (re-matches #"([A-Za-z_]\w*)\s*(?:=\s*(.+))?" item)]
          (when-not nm
            (throw (ex-info "unparseable enumerator" {:item item})))
          (let [named (into {} (map (juxt :name :value)) acc)
                v (if expr
                    (eval-c-expr expr #(if (contains? named %)
                                         (get named %)
                                         (macro-value % 0)))
                    (inc prev))]
            [(conj acc {:name nm :value v}) v])))
      [[] -1]
      items))))

(defn- typedef-block
  "The `typedef enum { … } <typedef>;` body for `typedef`, from the vendored
   tree. Refuses zero matches and refuses several — either means the oracle is
   not reading what the build compiles."
  [typedef]
  (let [pattern (re-pattern (str "typedef\\s+enum\\s*\\{([^{}]*)\\}\\s*"
                                 (java.util.regex.Pattern/quote typedef)
                                 "\\s*;"))
        hits (for [[path text] @headers
                   [_ body] (re-seq pattern text)]
               {:path path :body body})]
    (when-not (= 1 (count hits))
      (throw (ex-info "LVGL typedef must be declared exactly once in the vendored tree"
                      {:typedef typedef :matches (mapv :path hits)})))
    (first hits)))

(def ^:private header-facts
  "The oracle's output, in the extractor's own shape:
   `{typedef [{:name … :value …}]}` for exactly `lift/required-typedefs` —
   the set derived from the factory's own consumption, never restated."
  (delay (into {}
               (map (fn [typedef]
                      [typedef (parse-enum-body (:body (typedef-block typedef)))]))
               lift/required-typedefs)))

(def ^:private header-constructs
  "The oracle's facts lifted through the FACTORY's own naming pass, keyed by
   typedef. Reusing `lift` here is deliberate: the C-name -> keyword rule must
   have exactly one home, so this suite judges VALUES and not spelling."
  (delay (into {}
               (map (juxt :typedef-name identity))
               (lift/enum-edn->constructs @header-facts))))

;; ── Deriving what to check, from live sources only ──────────────────────────

(defn- binding-var
  "The generated bindings var named `<kebab proto-type>-<suffix>`, or nil.
   Mirrors `construct.emit`'s own naming so a var that should exist and does
   not is visible as nil rather than as a silent skip.

   Resolved by NAME rather than through a `:require` alias, because the whole
   point is to look for a var the emitter may not have written. An alias would
   make an absent var a compile error in this file instead of the finding it
   is."
  [proto-type suffix]
  (requiring-resolve
   (symbol "lvgl-codegen.generated.enums" (str (csk/->kebab-case-string proto-type) suffix))))

(def ^:private lut-tables
  "`renderer/generated/ui_luts.h` parsed back into
   `{typedef {:table \"flex_flow\" :entries [{:index :symbol :proto-name}]}}`.
   The entries are read from the C ARRAY; the index/proto-name come from the
   generated trailing comment and are cross-checked against the bindings, so a
   comment that drifted from the array is itself a finding."
  (delay
    (let [text (slurp luts-header-path)]
      (into {}
            (for [[_ typedef table body]
                  (re-seq #"(?s)static const (\w+) (\w+)_lut\[\] = \{(.*?)\};" text)]
              [typedef
               {:table table
                :entries
                (vec (for [line (str/split-lines body)
                           :when (not (str/blank? line))]
                       (let [[_ sym idx pname]
                             (re-matches #"\s*(\S+),\s*/\*\s*(\d+):\s*(\w+).*" line)]
                         (when-not sym
                           (throw (ex-info "unparseable ui_luts.h entry"
                                           {:typedef typedef :line line})))
                         {:index (Long/parseLong idx) :symbol sym :proto-name pname})))}])))))

(defn- lut-typedefs [] (set (keys @lut-tables)))

(defn- untabled-typedefs
  "Every required typedef whose generated map value IS the LVGL header value,
   because no table stands between them. Two populations, arriving there for
   different reasons and checked identically: the DIRECT-CAST proto-emitted
   families, where the registry number is forced to equal the header value by
   the parity contract; and the AUTHORING-ONLY families
   (`lift/authoring-only-typedefs`), which never get a proto enum at all and
   carry raw header values OR'd onto the wire.

   Derived by SUBTRACTING the LUT header's tables rather than by naming
   `lift`'s private direct-cast set, so a family that grows a table stops being
   checked here automatically and starts being checked through the table."
  []
  (set/difference (set lift/required-typedefs) (lut-typedefs)))

(defn- keyword->c-name
  "Authoring keyword -> the LVGL constant it is derived from, for one typedef."
  [typedef]
  (into {}
        (map (juxt :clj-keyword :c-name))
        (:members (get @header-constructs typedef))))

(defn- keyword->header-value
  "Authoring keyword -> the value the vendored header declares, for one
   typedef. THE authority every assertion below compares against."
  [typedef]
  (into {}
        (map (juxt :clj-keyword :resolved-int))
        (:members (get @header-constructs typedef))))

;; ── The oracle must be non-vacuous before anything reads it ─────────────────

(deftest oracle-reads-every-required-typedef
  (testing "every typedef the factory requires is found exactly once and parses"
    ;; Without this, an oracle that silently produced {} would make every
    ;; keyword check below iterate an empty collection and report green.
    (is (seq lift/required-typedefs)
        "lift/required-typedefs is empty - nothing downstream can be judged")
    (doseq [typedef (sort lift/required-typedefs)]
      (testing typedef
        (is (seq (get @header-facts typedef))
            (str typedef ": the vendored headers yielded no enumerators"))
        (is (seq (:members (get @header-constructs typedef)))
            (str typedef ": every enumerator was dropped as a sentinel"))))))

(deftest oracle-refuses-rather-than-skips
  (testing "an unknown token, identifier, directive or typedef is a throw"
    (is (thrown? clojure.lang.ExceptionInfo (eval-c-expr "1 $ 2" (fn [_] 0))))
    (is (thrown? clojure.lang.ExceptionInfo (eval-c-expr "1 2" (fn [_] 0))))
    (is (thrown? clojure.lang.ExceptionInfo (eval-c-expr "(1 << 2" (fn [_] 0))))
    (is (thrown? clojure.lang.ExceptionInfo (macro-value "LV_NO_SUCH_MACRO_ANYWHERE" 0)))
    (is (thrown? clojure.lang.ExceptionInfo (apply-preprocessor "#ifdef LV_USE_FLEX\nA,\n#endif")))
    (is (thrown? clojure.lang.ExceptionInfo (typedef-block "lv_no_such_typedef_t"))))
  (testing "the forms the vendored headers DO use evaluate"
    (is (= 12 (eval-c-expr "(1 << 2) | (1 << 3)" (fn [_] 0))))
    (is (= 255 (eval-c-expr "0xFFu" (fn [_] 0))))
    (is (= 5 (eval-c-expr "A | B" {"A" 1 "B" 4})))))

;; ── Leg 1: keyword -> wire int, for every family with no table ──────────────

(deftest untabled-families-carry-the-header-value
  (testing "every generated keyword->int entry equals the vendored header value"
    (is (seq (untabled-typedefs)) "no untabled families derived - nothing judged")
    (doseq [typedef (sort (untabled-typedefs))]
      (let [{:keys [proto-type]} (get @header-constructs typedef)
            v (binding-var proto-type "-keyword->int")
            expected (keyword->header-value typedef)
            c-names (keyword->c-name typedef)]
        (testing typedef
          (is (some? v)
              (str typedef ": no `" (csk/->kebab-case-string proto-type)
                   "-keyword->int` in lvgl-codegen.generated.enums"))
          (when v
            (let [actual @v]
              ;; TOTALITY, both directions and stated separately so the failure
              ;; says WHICH direction broke.
              (is (empty? (set/difference (set (keys expected)) (set (keys actual))))
                  (str typedef ": header enumerators with no authoring keyword: "
                       (sort (set/difference (set (keys expected)) (set (keys actual))))))
              (is (empty? (set/difference (set (keys actual)) (set (keys expected))))
                  (str typedef ": authoring keywords with no header enumerator: "
                       (sort (set/difference (set (keys actual)) (set (keys expected))))))
              (doseq [[kw want] (sort expected)]
                (is (= want (get actual kw))
                    (str typedef " " kw " (" (get c-names kw) "): header says " want
                         ", bindings say " (get actual kw)))))))))))

;; ── Leg 2: keyword -> wire int -> LUT -> LVGL value ─────────────────────────

(deftest lut-families-resolve-through-the-table-to-the-header-value
  (testing "every LUT entry is the constant its wire number's keyword names"
    (is (seq (lut-typedefs)) "ui_luts.h declared no tables - nothing judged")
    (doseq [typedef (sort (lut-typedefs))]
      (let [{:keys [proto-type]} (get @header-constructs typedef)
            _ (is (contains? lift/proto-emitted-typedefs typedef)
                  (str typedef ": ui_luts.h has a table for a typedef the factory does not emit"))
            {:keys [entries table]} (get @lut-tables typedef)
            ;; `some->` rather than a bare deref: a var the emitter failed to
            ;; write must FAIL naming itself, not NPE into an ERROR that
            ;; executes none of the clauses below.
            kw->int (some-> (binding-var proto-type "-keyword->int") deref)
            kw->member (some-> (binding-var proto-type "-keyword->member") deref)
            header-values (keyword->header-value typedef)
            c-names (keyword->c-name typedef)
            by-index (into {} (map (juxt :index identity)) entries)]
        (testing typedef
          (is (some? kw->int) (str typedef ": no keyword->int bindings for a LUT family"))
          (is (some? kw->member) (str typedef ": no keyword->member bindings for a LUT family"))
          ;; The C array's NAME is what `renderer.c` indexes, and `construct.emit`
          ;; derives it from the proto type. A rename here is caught by the C
          ;; compiler eventually; caught HERE it names the reason.
          (is (= (csk/->snake_case_string proto-type) table)
              (str typedef ": ui_luts.h array is `" table
                   "_lut`, the proto type derives `"
                   (csk/->snake_case_string proto-type) "_lut`"))
          ;; The array must be exactly the wire-number space: indexed 0..N-1,
          ;; one entry per keyword, no gap and no tail.
          (is (= (vec (range (count entries))) (mapv :index entries))
              (str typedef ": ui_luts.h indices are not contiguous from 0"))
          (is (= (count entries) (count kw->int))
              (str typedef ": " (count entries) " table entries for "
                   (count kw->int) " authoring keywords"))
          (doseq [[kw wire] (sort-by val kw->int)]
            (let [entry (get by-index wire)
                  c-name (get c-names kw)]
              (is (some? entry)
                  (str typedef " " kw ": wire number " wire " has no ui_luts.h entry"))
              (when entry
                ;; The generated trailing comment must agree with the bindings,
                ;; so a drifted comment cannot make a wrong row look right.
                (is (= (some-> (get kw->member kw) name) (:proto-name entry))
                    (str typedef " " kw " wire " wire
                         ": ui_luts.h names member " (:proto-name entry)
                         ", bindings say " (get kw->member kw)))
                (if c-name
                  (do
                    ;; SYMBOLIC identity: the table cell must be the very
                    ;; constant this keyword is derived from.
                    (is (= c-name (:symbol entry))
                        (str typedef " " kw " wire " wire
                             ": ui_luts.h maps it to " (:symbol entry)
                             ", the header constant for this keyword is " c-name))
                    ;; And the VALUE the renderer ends up with, from the
                    ;; vendored header, must be this keyword's own value. This
                    ;; is the composed claim; the symbolic check above is how
                    ;; the failure names the entry.
                    (is (= (get header-values kw)
                           (get (into {} (map (juxt :name :value))
                                      (get @header-facts typedef))
                                (:symbol entry)))
                        (str typedef " " kw " wire " wire
                             ": resolves to the value of " (:symbol entry)
                             ", not to " c-name)))
                  ;; A member with no header constant is a proto-only
                  ;; synthetic; the emitter writes a literal 0 for it.
                  (is (= "0" (:symbol entry))
                      (str typedef " " kw " wire " wire
                           ": no LVGL constant is derived from this keyword, so"
                           " ui_luts.h must carry 0, not " (:symbol entry)))))))
          ;; Totality against the header, same both-directions shape as leg 1.
          (let [synthetic (set/difference (set (keys kw->int)) (set (keys header-values)))]
            (is (empty? (set/difference (set (keys header-values)) (set (keys kw->int))))
                (str typedef ": header enumerators with no authoring keyword: "
                     (sort (set/difference (set (keys header-values)) (set (keys kw->int))))))
            (doseq [kw (sort synthetic)]
              (is (= "0" (:symbol (get by-index (get kw->int kw))))
                  (str typedef " " kw ": treated as a proto-only synthetic but"
                       " ui_luts.h does not carry 0 for it")))))))))

;; ── Leg 3: keyword -> proto member spelling ─────────────────────────────────

(deftest keyword-member-maps-name-the-header-constant
  (testing "every keyword->member entry spells the constant the keyword names"
    (doseq [typedef (sort lift/proto-emitted-typedefs)]
      (let [{:keys [proto-type]} (get @header-constructs typedef)
            v (binding-var proto-type "-keyword->member")
            c-names (keyword->c-name typedef)
            header-names (into #{} (map :name) (get @header-facts typedef))]
        (testing typedef
          (is (some? v)
              (str typedef ": no `" (csk/->kebab-case-string proto-type)
                   "-keyword->member` in lvgl-codegen.generated.enums"))
          (when v
            (let [actual @v]
              (is (empty? (set/difference (set (keys c-names)) (set (keys actual))))
                  (str typedef ": header enumerators with no keyword->member entry: "
                       (sort (set/difference (set (keys c-names)) (set (keys actual))))))
              (doseq [[kw member] (sort actual)]
                (let [spelled (str "LV_" (name member))]
                  (if-let [c-name (get c-names kw)]
                    (is (= c-name spelled)
                        (str typedef " " kw ": member " member " spells " spelled
                             ", the header constant for this keyword is " c-name))
                    (is (not (contains? header-names spelled))
                        (str typedef " " kw ": member " member
                             " has no keyword of its own but " spelled
                             " IS declared in the vendored header"))))))))))))

;; ── Leg 4: the hand-written map `construct-bindings` cannot see ─────────────

(def ^:private layout-vocabulary
  "The `:layout` authoring keywords, read out of the authoring schema
   (`lvgl-codegen.schema/widget-node`) rather than restated. Refuses unless
   exactly one `[:layout … [:enum …]]` entry exists."
  (delay
    (let [hits (->> (tree-seq coll? seq schema/widget-node)
                    (filter vector?)
                    (filter #(= :layout (first %)))
                    (keep #(first (filter (fn [x] (and (vector? x) (= :enum (first x)))) %))))]
      (when-not (= 1 (count hits))
        (throw (ex-info "expected exactly one :layout [:enum …] in the authoring schema"
                        {:matches (count hits)})))
      (set (rest (first hits))))))

(defn- flow-keyword-for
  "The FlexFlow authoring keyword a `:layout` keyword abbreviates, derived by
   SHAPE rather than by a table: drop the `flex-` prefix, then require exactly
   one FlexFlow keyword with the same number of segments, each of which the
   layout keyword's segment is a prefix of (`col` -> `column`, `row` -> `row`).

   Deriving it is the point. A literal `{:flex-col :column}` table here would
   be a second spelling of the very mapping under test, and the test would then
   assert the table against itself. An ambiguous or absent match REFUSES."
  [layout-kw candidates]
  (let [segs (str/split (str/replace (name layout-kw) #"^flex-" "") #"-")
        matches (filter (fn [cand]
                          (let [cs (str/split (name cand) #"-")]
                            (and (= (count cs) (count segs))
                                 (every? true? (map str/starts-with? cs segs)))))
                        candidates)]
    (when-not (= 1 (count matches))
      (throw (ex-info "layout keyword does not abbreviate exactly one FlexFlow keyword"
                      {:layout layout-kw :matches (vec matches)})))
    (first matches)))

(deftest layout-map-is-total-over-the-authoring-vocabulary
  (testing "every :layout keyword the schema admits has a mapping, and no more"
    (is (seq @layout-vocabulary) "the authoring schema declared no :layout keywords")
    (is (= @layout-vocabulary (set (keys emit-proto/layout-flow-keyword->member)))
        (str "unjudged/unknown :layout keywords - schema: "
             (sort @layout-vocabulary)
             ", emit-proto: " (sort (keys emit-proto/layout-flow-keyword->member))))))

(deftest layout-map-resolves-to-the-header-value
  (testing "each hand-written :layout pair means the LVGL constant it names"
    (let [typedef "lv_flex_flow_t"
          {:keys [proto-type]} (get @header-constructs typedef)
          kw->member (some-> (binding-var proto-type "-keyword->member") deref)
          c-names (keyword->c-name typedef)
          by-name (into {} (map (juxt :name :value)) (get @header-facts typedef))
          ;; NEVER a bare `(get by-name c)`: an absent constant would then read
          ;; as nil on BOTH sides and the comparison would pass on nothing at
          ;; all. A miss becomes a value that can only equal itself.
          lvgl-value (fn [c] (if (contains? by-name c)
                               (get by-name c)
                               (str "<no LVGL constant named " c ">")))
          candidates (keys kw->member)]
      (is (seq candidates) "FlexFlow bindings are empty - nothing judged")
      (doseq [layout-kw (sort @layout-vocabulary)]
        (let [flow-kw (flow-keyword-for layout-kw candidates)
              want (get kw->member flow-kw)
              got (get emit-proto/layout-flow-keyword->member layout-kw)]
          (is (= want got)
              (str layout-kw " abbreviates " flow-kw
                   ", whose proto member is " want " (" (get c-names flow-kw) ")"
                   " - emit-proto maps it to " got))
          ;; And say it in LVGL values, which is what actually reaches the
          ;; renderer: the constant behind the emitted member must be the
          ;; constant behind the keyword.
          (is (= (lvgl-value (get c-names flow-kw))
                 (lvgl-value (str "LV_" (some-> got name))))
              (str layout-kw ": emits " got " => LVGL value "
                   (lvgl-value (str "LV_" (some-> got name)))
                   ", but " flow-kw " names " (get c-names flow-kw)
                   " => " (lvgl-value (get c-names flow-kw))))))
      (testing "and the abbreviation is onto: no FlexFlow value is unreachable"
        ;; The synthetic FLEX_FLOW_NONE has no `:layout` spelling by design —
        ;; it is what an absent :layout emits — so it is excluded by having no
        ;; header constant, not by being named here.
        (is (= (set (remove #(nil? (get c-names %)) candidates))
               (set (map #(flow-keyword-for % candidates) @layout-vocabulary)))
            "some header-backed FlexFlow value has no :layout keyword")))))

;; ── Leg 5: the same mapping's OTHER two hand-written spellings ──────────────
;; Neither is a keyword->wire map, but both are the same defect class: an LVGL
;; number written out by hand where nothing compares it to the header. They are
;; judged here because the oracle that can judge them already exists.

(defn- function-macro
  "`#define NAME(param) body` from the vendored headers, as `[param body]`.
   Refuses an absent or ambiguous definition, like every other lookup here."
  [nm]
  (let [pattern (re-pattern (str "(?m)^[ \\t]*#[ \\t]*define[ \\t]+"
                                 (java.util.regex.Pattern/quote nm)
                                 "\\(([A-Za-z_]\\w*)\\)[ \\t]+(\\S.*)$"))
        hits (into #{} (for [[_ text] @headers
                             [_ param body] (re-seq pattern text)]
                         [param (str/trim body)]))]
    (when-not (= 1 (count hits))
      (throw (ex-info "function-like macro must be defined exactly once"
                      {:macro nm :definitions hits})))
    (first hits)))

(deftest grid-track-constants-match-the-vendored-macros
  (testing "emit-proto's hand-carried grid coords equal the lv_grid.h macros"
    ;; `encode-grid-track` turns an authoring track into the int the renderer
    ;; hands straight to `lv_obj_set_grid_dsc_array`, using two constants
    ;; transcribed from the headers into this namespace. `factory`'s
    ;; `hand-carried-mirror-violations` pairs the `LV_STATE_*`/`LV_PART_*`
    ;; constants with their headers; these two are in NO such pairing, so
    ;; before this test nothing at all compared them.
    ;; Private vars deliberately: reading them here is a test concern and does
    ;; not widen the namespace's API.
    (is (= (macro-value "LV_COORD_MAX" 0) @#'emit-proto/lv-coord-max)
        "emit-proto/lv-coord-max disagrees with LV_COORD_MAX")
    (is (= (macro-value "LV_GRID_CONTENT" 0) @#'emit-proto/lv-grid-content)
        "emit-proto/lv-grid-content disagrees with LV_GRID_CONTENT")
    (testing ":content encodes to LV_GRID_CONTENT"
      (is (= (macro-value "LV_GRID_CONTENT" 0)
             (#'emit-proto/encode-grid-track :content))))
    (testing "[:fr n] encodes to LV_GRID_FR(n) for every n the schema admits"
      ;; The macro is function-like, so it is EVALUATED with the argument
      ;; substituted rather than pattern-matched: a reshuffled body
      ;; (`LV_COORD_MAX - 99 + x - 1`) still has to agree numerically.
      (let [[param body] (function-macro "LV_GRID_FR")]
        (doseq [n [1 2 3 7 12]]
          (let [substituted (str/replace body
                                         (re-pattern (str "\\b"
                                                          (java.util.regex.Pattern/quote param)
                                                          "\\b"))
                                         (str n))]
            (is (= (eval-c-expr substituted #(macro-value % 0))
                   (#'emit-proto/encode-grid-track [:fr n]))
                (str "[:fr " n "] disagrees with LV_GRID_FR(" n ")"))))))
    (testing "a px track passes through unchanged"
      (is (= 42 (#'emit-proto/encode-grid-track 42))))))

(deftest coverage-matrix-reference-values-match-the-vendored-headers
  (testing "the matrix's FLEX_VALS are the LVGL values its FLEX_KWS name"
    ;; `renderer/coverage_matrix/run.sh` drives the ONLY lane that renders all
    ;; eight flows. Its proto side authors the `:layout` keyword; its reference
    ;; side writes the raw LVGL value from a hand-kept parallel array. The lane
    ;; compares framebuffers, so a wrong pair reds it ONLY IF the two flows it
    ;; confuses happen to render differently in that fixture — which is a
    ;; property of the fixture, not of the mapping. Comparing the array to the
    ;; headers is unconditional.
    (let [text (slurp coverage-matrix-path)
          array (fn [nm]
                  (let [hits (re-seq (re-pattern (str "(?s)\\b" nm "=\\((.*?)\\)")) text)]
                    (when-not (= 1 (count hits))
                      (throw (ex-info "shell array must be assigned exactly once"
                                      {:array nm :matches (count hits)})))
                    (str/split (str/trim (second (first hits))) #"\s+")))
          kws (mapv keyword (array "FLEX_KWS"))
          ref-vals (mapv #(Long/parseLong %) (array "FLEX_VALS"))
          by-name (into {} (map (juxt :name :value)) (get @header-facts "lv_flex_flow_t"))
          ;; Resolved through the SHAPE derivation, never through
          ;; `emit-proto/layout-flow-keyword->member`. Routing it through the
          ;; emit map would make a defect in that map red this test too, and a
          ;; red that two clauses can produce attributes to neither.
          c-names (keyword->c-name "lv_flex_flow_t")
          candidates (keys c-names)]
      (is (seq kws) "FLEX_KWS is empty - nothing judged")
      (is (= (count kws) (count ref-vals))
          (str "FLEX_KWS has " (count kws) " entries, FLEX_VALS has " (count ref-vals)))
      ;; TOTALITY: the matrix must drive exactly the authoring vocabulary. A
      ;; keyword the schema admits but the matrix never renders is an unjudged
      ;; flow, and a keyword the matrix renders but the schema rejects is a
      ;; fixture that cannot be authored.
      (is (= @layout-vocabulary (set kws))
          (str "matrix FLEX_KWS vs the authoring :layout vocabulary - matrix: "
               (sort kws) ", schema: " (sort @layout-vocabulary)))
      (doseq [[kw v] (map vector kws ref-vals)]
        (let [c-name (get c-names (flow-keyword-for kw candidates))]
          (is (= (get by-name c-name) v)
              (str "matrix reference value for " kw " is " v
                   ", but " c-name " is " (get by-name c-name)
                   " in the vendored header")))))))

;; ── Leg 6: the CLASS TOKEN, one level above the :layout keyword ──────────────
;; Legs 4 and 5 begin at the `:layout` authoring keyword. Nothing above them
;; asked where that keyword comes from, and for every screen authored in the
;; class DSL it comes from `lvgl-codegen.expand`: `layout-directives` says which
;; tokens are layout tokens at all, and `extract-layout` says which flow each
;; one selects. Both are hand-written, neither is extracted from anything, and
;; before this leg neither was read by any test in the tree.
;;
;; The defect this catches is the same wrong-but-legal one: point "flex-col" at
;; `:flex-row-wrap` and the token still parses, the keyword is still one the
;; schema admits, `emit-proto` still finds a FlexFlow member for it, `ui_luts.h`
;; still resolves that member to a real LVGL constant, and the C compiler has
;; nothing to object to. Only the pixels are wrong.

(defn- class-layout-directives
  "The class tokens `expand/parse-class-token` diverts away from style parsing,
   read out of the LIVE private var. Private on purpose - reading it here is a
   test concern and does not widen `expand`'s API, the same call
   `grid-track-constants-match-the-vendored-macros` makes about `emit-proto`.
   Restating the token set in this file would BE the second spelling this leg
   exists to prevent, and the test would then compare a copy against itself."
  []
  @#'expand/layout-directives)

(defn- layout-keyword->lvgl-value
  "The value the vendored header declares for a `:layout` authoring keyword.

   Resolved through the SHAPE derivation (`flow-keyword-for`) and the header
   oracle, NEVER through `emit-proto/layout-flow-keyword->member`: routing it
   through that map would let a defect in leg 4's subject red leg 6 as well, and
   a red two clauses can produce attributes to neither."
  [layout-kw]
  (let [c-names (keyword->c-name "lv_flex_flow_t")
        by-name (into {} (map (juxt :name :value)) (get @header-facts "lv_flex_flow_t"))
        c-name (get c-names (flow-keyword-for layout-kw (keys c-names)))]
    (when-not (contains? by-name c-name)
      (throw (ex-info "no vendored LVGL constant behind this :layout keyword"
                      {:layout layout-kw :c-name c-name})))
    (get by-name c-name)))

(deftest class-tokens-select-the-flow-they-name
  (testing "each layout class token selects the flow its own name spells"
    (let [directives (class-layout-directives)]
      (is (seq directives) "expand/layout-directives is empty - nothing judged")
      (is (seq @layout-vocabulary) "the authoring schema declared no :layout keywords")
      ;; TOTAL over the authoring vocabulary. Every `:layout` keyword the schema
      ;; admits is judged, and the two answers are stated separately so the
      ;; failure says which one broke: a keyword the DSL SPELLS must be selected
      ;; by that spelling, and a keyword it does not spell must select NOTHING
      ;; rather than quietly selecting some other flow.
      (doseq [kw (sort @layout-vocabulary)]
        (let [token (name kw)
              got (expand/extract-layout token)]
          (if (contains? directives token)
            (is (= kw got)
                (str "class token \"" token "\" selects " (pr-str got)
                     ", but it spells " kw))
            (is (nil? got)
                (str "class token \"" token "\" is not in expand/layout-directives"
                     " yet extract-layout selects " (pr-str got))))))
      ;; TOTAL over the directive set, the other direction. A token the parser
      ;; diverts away from style parsing but `extract-layout` then drops is the
      ;; quiet failure: the class string still parses clean, no exception is
      ;; thrown, and the widget simply has no layout.
      (doseq [token (sort directives)]
        (let [got (expand/extract-layout token)]
          (is (contains? @layout-vocabulary got)
              (str "class token \"" token "\" selects " (pr-str got)
                   ", which the authoring schema's :layout enum does not admit"))))
      ;; The two hand-written spellings INSIDE expand.clj must agree with each
      ;; other. `layout-directives` gates `parse-class-token`; `extract-layout`
      ;; carries its own token literals. A token in one and not the other is a
      ;; silent hole in whichever direction it points, so both are asserted.
      (doseq [token (sort directives)]
        (is (nil? (expand/parse-class-token token))
            (str "\"" token "\" is a layout directive, so parse-class-token must"
                 " divert it rather than parse it as a style token")))
      (doseq [kw (sort @layout-vocabulary)
              :let [token (name kw)]
              :when (not (contains? directives token))]
        (is (thrown? clojure.lang.ExceptionInfo (expand/parse-class-token token))
            (str "\"" token "\" names a :layout keyword the class DSL does not"
                 " admit, so it must be REFUSED as a class token rather than"
                 " silently ignored"))))))

(deftest bare-class-directive-selects-the-lvgl-default-flow
  (testing "a layout token that spells no :layout keyword abbreviates the default"
    ;; `flex` is an ABBREVIATION rather than a spelling: no `:layout` keyword is
    ;; named `:flex`, so the identity clause above cannot reach it. Left there
    ;; it would be the one token in the set judged by nothing - the "clean" and
    ;; "I could not look" green this suite refuses everywhere else.
    ;;
    ;; The oracle is LVGL's own default, not a table written here. An unset
    ;; style property resolves to a zero `lv_style_value_t`
    ;; (`lv_style_prop_get_default` has no case for `LV_STYLE_FLEX_FLOW`, so it
    ;; falls to the `default:` arm), which means an object given flex layout
    ;; with no explicit flow lays out as the `lv_flex_flow_t` enumerator whose
    ;; value is 0. CSS states the same fact as `flex-direction: row` being the
    ;; initial value.
    ;;
    ;; ARGUED, NOT EXTRACTED, and it is a deftest of its own for exactly that
    ;; reason. "A bare directive means the default" is this suite's reading of
    ;; the DSL; the vendored header supplies only the number behind it. A
    ;; renumbered `lv_flex_flow_t` would red this without `expand.clj` having
    ;; any defect, and keeping it separate stops that red from being mistaken
    ;; for the derived clause above going off.
    (let [directives (class-layout-directives)
          aliases (remove #(contains? @layout-vocabulary (keyword %)) directives)
          defaults (filter #(zero? (layout-keyword->lvgl-value %)) @layout-vocabulary)]
      (is (= 1 (count defaults))
          (str "lv_flex_flow_t must declare exactly one zero-valued flow for"
               " \"the default\" to name anything - got " (sort defaults)))
      (doseq [token (sort aliases)]
        (let [got (expand/extract-layout token)]
          (is (= (first defaults) got)
              (str "class token \"" token "\" spells no :layout keyword, so it"
                   " abbreviates the LVGL default flow " (first defaults)
                   " - extract-layout selects " (pr-str got))))))))

;; ── Leg 7: the hand-carried lv_style_selector_t constants ───────────────────
;;
;; `lvgl-codegen.style-props` hand-carries a handful of `lv_state_t` and
;; `lv_part_t` values as `^:const` longs, and `expand->variants` ORs them into
;; the `StyleGroup.state_selector` that `renderer.c` hands to
;; `lv_obj_add_style` VERBATIM. A wrong value there is legal proto, compiles,
;; regenerates identically, and silently binds the style to the WRONG state or
;; part — the same wrong-but-legal class the legs above cover for the GENERATED
;; maps, arriving in the one family that is hand-written rather than emitted.
;;
;; `factory/hand-carried-mirror-violations` already pairs each constant with
;; the C name it must equal, but it is reachable only through `renderer.mk`'s
;; `construct-bindings`, which needs clang and a compiled probe. These clauses
;; re-ask the same question against this file's own pure-Clojure header oracle:
;; available wherever `clojure -M:test` is, and a SECOND opinion rather than one
;; extractor agreeing with itself.

(defn- selector-constant-values
  "C constant name → value for the two typedefs `style-props` carries from.
   Parsed by this suite's own oracle, never through the factory's extractor."
  []
  (into {}
        (for [typedef ["lv_state_t" "lv_part_t"]
              {c-name :name value :value} (parse-enum-body (:body (typedef-block typedef)))]
          [c-name value])))

(defn- hand-carried-selector-vars
  "Every `lv-state-*` / `lv-part-*` constant `style-props` declares, as
   `var-name → value`. Read from the LIVE namespace rather than listed here:
   a roster in this file would be the second spelling this leg exists to
   prevent, and a newly added constant would join it only if someone
   remembered — which is the exact omission the clause below is about."
  []
  (into {}
        (for [[sym v] (ns-publics 'lvgl-codegen.style-props)
              :let [var-name (name sym)]
              :when (or (str/starts-with? var-name "lv-state-")
                        (str/starts-with? var-name "lv-part-"))]
          [var-name @v])))

(deftest hand-carried-selector-constants-match-the-vendored-headers
  (testing "every lvgl-mirrored-constants pair equals its vendored constant"
    (let [by-name (selector-constant-values)]
      (is (seq by-name)
          "the vendored headers yielded no lv_state_t/lv_part_t enumerators")
      (is (seq factory/lvgl-mirrored-constants)
          "factory/lvgl-mirrored-constants is empty - nothing judged")
      (doseq [[c-name carried] (sort factory/lvgl-mirrored-constants)]
        ;; An ABSENT constant is a violation, not a skip: it means the header
        ;; stopped declaring it (a rename or removal across an LVGL major),
        ;; which is precisely the drift the pairing exists to catch.
        (is (contains? by-name c-name)
            (str c-name ": paired in lvgl-mirrored-constants but declared by no"
                 " vendored header"))
        (when (contains? by-name c-name)
          (is (= (get by-name c-name) carried)
              (str c-name ": the vendored header says " (get by-name c-name)
                   ", style-props carries " carried)))))))

(deftest every-hand-carried-selector-constant-is-paired
  (testing "no lv-state-*/lv-part-* constant escapes the mirror pairing"
    ;; `lvgl-mirrored-constants` lives in `factory` rather than beside the
    ;; constants because `style_props.clj` is byte-mirrored into a consumer
    ;; with no `construct/` namespaces. Its own docstring names the cost: a
    ;; newly added constant "does not pair itself", leaving it hand-carried and
    ;; UNGUARDED while every sibling is checked. That cost was carried by a
    ;; prose note at the definitions; this clause makes it mechanical.
    (let [declared (hand-carried-selector-vars)
          paired (set (vals factory/lvgl-mirrored-constants))]
      (is (seq declared)
          "style-props declares no lv-state-*/lv-part-* constants - nothing judged")
      (doseq [[var-name value] (sort declared)]
        (is (contains? paired value)
            (str "style-props/" var-name " (" value ") is hand-carried from the"
                 " LVGL headers but appears in no lvgl-mirrored-constants pair,"
                 " so nothing compares it against lv_obj_style.h"))))))

(deftest state-selector-entries-are-authorable-through-both-surfaces
  (testing "every state key reaches the class DSL, the :style map and the wire"
    ;; `state-selector` is the ONE home four consumers derive from: the
    ;; class-DSL prefix parse and the nested `:style` schema (the two authoring
    ;; surfaces), and `expand->variants`' emitted selector (the wire). A key
    ;; present in the map but unreachable through either surface, or reaching
    ;; the wire as the wrong int, is invisible to every other lane here.
    (is (seq style-props/state-selector) "state-selector is empty - nothing judged")
    (doseq [[state-kw selector] (sort-by key style-props/state-selector)]
      (testing (str state-kw)
        ;; A LITERAL-valued prop (`:int` parse kind) so no design-token
        ;; registry is needed: this leg is about the STATE axis, and a token
        ;; lookup would let an unrelated token change red it.
        (let [parsed (expand/parse-class-token (str (name state-kw) ":flex-grow-2"))]
          (is (= state-kw (:state parsed))
              (str "class prefix \"" (name state-kw) ":\" parses to state "
                   (pr-str (:state parsed)) ", not " state-kw))
          ;; `{:tokens {}}` rather than `{}`: the arrow-spec requires the key,
          ;; and an EMPTY registry is the point — a literal-valued prop must
          ;; resolve without one.
          (let [groups (expand/expand->variants {:tokens {}} [parsed])]
            (is (= [selector] (mapv :state-selector groups))
                (str "\"" (name state-kw) ":flex-grow-2\" emits state_selector "
                     (pr-str (mapv :state-selector groups)) ", not " selector))))
        ;; The validity is computed OUTSIDE the assertion on purpose.
        ;; `(is (m/validate style-schema …))` prints the evaluated arguments on
        ;; failure, and `style-schema` is the whole closed prop registry crossed
        ;; with every state and breakpoint — a single ~570 kB line that buries
        ;; the message and makes the mutation-evidence file unreadable. A bound
        ;; boolean fails as `actual: false`.
        (let [style-map-accepted? (m/validate schema/style-schema {state-kw {:flex-grow 2}})]
          (is style-map-accepted?
              (str ":style {" state-kw " {…}} is rejected by style-schema, so the"
                   " nested authoring surface cannot reach this state")))))))

(deftest pending-names-the-lvgl-user-bit-it-is-built-on
  (testing ":pending is LV_STATE_USER_1 as the vendored header declares it"
    ;; The one `state-selector` entry whose authoring name is NOT its
    ;; constant's, so the name-derived clauses above cannot reach it: the
    ;; header calls the bit USER_1 and says nothing about what it means, and
    ;; the domain name is chosen here. That indirection is exactly why the pair
    ;; is asserted explicitly rather than left to the totality clauses.
    (let [by-name (selector-constant-values)]
      (is (contains? by-name "LV_STATE_USER_1")
          "lv_obj_style.h declares no LV_STATE_USER_1 - the user-bit extension
           point this state is built on is gone")
      (is (= (get by-name "LV_STATE_USER_1") style-props/lv-state-user-1)
          (str "style-props/lv-state-user-1 is " style-props/lv-state-user-1
               ", the vendored header says " (get by-name "LV_STATE_USER_1")))
      (is (= style-props/lv-state-user-1 (:pending style-props/state-selector))
          ":pending does not resolve to lv-state-user-1")
      ;; DISJOINTNESS. The user bits are an extension point, so the value must
      ;; not collide with a state LVGL itself defines — a collision would make
      ;; `pending:` silently style a semantic state instead.
      (doseq [[c-name value] (sort by-name)
              :when (and (str/starts-with? c-name "LV_STATE_")
                         (not= c-name "LV_STATE_USER_1")
                         (not= c-name "LV_STATE_ANY"))]
        (is (not= value style-props/lv-state-user-1)
            (str "LV_STATE_USER_1 collides with " c-name " (" value ")"))))))
