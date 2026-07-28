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
   instance — eight `:layout` keywords hand-paired to eight FlexFlow proto
   members, where a swapped pair is legal proto, compiles, regenerates
   identically, and renders the wrong flow.

   WHAT THIS SUITE ADDS.
   1. An INDEPENDENT oracle for the header values. `construct-bindings` and the
      committed bindings share one extractor; a defect in it agrees with
      itself. This namespace re-reads `renderer/lvgl/**.h` in pure Clojure and
      resolves each enumerator's value from the header text — a second opinion,
      not a second copy. It runs with no clang, no gcc, no jq and no network,
      which is why it can live in the cheap hermetic lane that runs on every
      battery rather than only where the toolchain does.
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
            [lvgl-codegen.construct.lift :as lift]
            [lvgl-codegen.emit-proto :as emit-proto]
            [lvgl-codegen.schema :as schema]))

(set! *warn-on-reflection* true)

;; Paths are CWD-relative to tools/renderer-gen — the directory `renderer.mk`'s
;; `clj-schema-test` cd's into, and the same convention theme_style_groups_test
;; uses.
(def ^:private lvgl-src-dir "../../renderer/lvgl/src")

(def ^:private lv-conf-path "../../renderer/lv_conf.h")

(def ^:private luts-header-path "../../renderer/generated/ui_luts.h")

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
   a default."
  (delay (into {}
               (map (fn [[_ nm body]] [nm (str/trim body)]))
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
   Refuses a name absent there rather than assuming a default."
  [nm]
  (if-let [body (get @lv-conf-macros nm)]
    (not (zero? (eval-c-expr body #(macro-value % 0))))
    (throw (ex-info "enum body is guarded by a macro absent from lv_conf.h"
                    {:macro nm :conf lv-conf-path}))))

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

(defn- direct-typedefs
  "Every required typedef whose wire int IS the LVGL value — i.e. everything
   without a generated lookup table. Derived from the LUT header, so a family
   that grows a table stops being checked as direct-cast automatically."
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

;; ── Leg 1: keyword -> wire int, for every DIRECT-CAST family ────────────────

(deftest direct-cast-keywords-carry-the-header-value
  (testing "every generated keyword->int entry equals the vendored header value"
    (is (seq (direct-typedefs)) "no direct-cast families derived - nothing judged")
    (doseq [typedef (sort (direct-typedefs))]
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
            {:keys [entries]} (get @lut-tables typedef)
            kw->int @(binding-var proto-type "-keyword->int")
            kw->member @(binding-var proto-type "-keyword->member")
            header-values (keyword->header-value typedef)
            c-names (keyword->c-name typedef)
            by-index (into {} (map (juxt :index identity)) entries)]
        (testing typedef
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
                (is (= (name (get kw->member kw)) (:proto-name entry))
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
          kw->member @(binding-var proto-type "-keyword->member")
          c-names (keyword->c-name typedef)
          by-name (into {} (map (juxt :name :value)) (get @header-facts typedef))
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
          (is (= (get by-name (get c-names flow-kw))
                 (get by-name (str "LV_" (name got))))
              (str layout-kw ": emits " got " => LVGL value "
                   (get by-name (str "LV_" (name got)))
                   ", but " flow-kw " names " (get c-names flow-kw)
                   " => " (get by-name (get c-names flow-kw))))))
      (testing "and the abbreviation is onto: no FlexFlow value is unreachable"
        ;; The synthetic FLEX_FLOW_NONE has no `:layout` spelling by design —
        ;; it is what an absent :layout emits — so it is excluded by having no
        ;; header constant, not by being named here.
        (is (= (set (remove #(nil? (get c-names %)) candidates))
               (set (map #(flow-keyword-for % candidates) @layout-vocabulary)))
            "some header-backed FlexFlow value has no :layout keyword")))))
