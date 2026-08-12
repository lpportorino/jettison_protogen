(ns lvgl-codegen.patch-test
  "Pins `guarded-arm-fields` against the C GUARDS IT CLAIMS TO MIRROR.

  WHAT THE TABLE IS FOR. `patch/needs-replace?` decides whether an incremental
  screen update can be expressed as UPDATE_PROPS or needs REPLACE_NODE. A field the
  renderer applies behind a `0 means keep` guard is a ONE-WAY DOOR: re-applying
  props in place cannot drive it back to 0, because the renderer will skip the 0.
  So a field guarded in C and missing from this table produces a morph that the
  renderer silently declines to fully apply — the widget keeps a stale value, with
  no error at either end.

  WHY THIS TEST IS DERIVED AND NOT A SECOND LIST. Three `:value` entries were
  missing — slider, arc and spinbox — and a hand-written expectation would have
  been written from the same misreading that produced the omission. So the oracle
  is `renderer/src/renderer.c` itself: the morph guards are parsed out of the C and
  the table is required to cover them. A hand-transcribed mirror of a header is the
  anti-pattern `.claude/rules/widget-consumer-duty.md` §3 names, and this table WAS
  one.

  WHY THE CURRENT CORPUS COULD NOT CATCH IT. No morph fixture drives a slider, arc
  or spinbox value to exactly 0, so the defect was unreachable from every pixel
  oracle and from the dual-engine morph parity lane alike — confirmed by
  regenerating every morph fixture with the fix applied and getting byte-identical
  output. A gate that only renders what the corpus happens to contain cannot find
  this class; only a derived comparison against the C can.

  SCOPE, stated so a green is not over-read. This judges the MORPH guards —
  `if (!morph_in_progress || p->F != 0)` — which are unambiguous to parse and are
  the class the defect belonged to. The renderer also carries plain
  `if (p->F != 0)` guards, which are one-way doors by the same argument and are
  NOT parsed here; those entries in the table remain reviewed by hand."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [lvgl-codegen.patch :as patch]))

(def ^:private renderer-c "../../renderer/src/renderer.c")

(defn morph-guarded-fields
  "Parse `renderer.c` for `if (!morph_in_progress || p->FIELD != 0)` and return
  `{:arm_props #{:field …}}`, keyed by the enclosing `case ui_WidgetNode_*_tag`.

  A single forward scan tracking the most recent arm tag, because that is what the
  C's own structure gives: each `case` opens a block and the guards inside it belong
  to that arm. Anything guarded outside an arm case is ignored rather than
  mis-attributed."
  [src]
  (second
   (reduce
    (fn [[arm acc] line]
      (cond
        (re-find #"case ui_WidgetNode_(\w+)_tag:" line)
        [(keyword (second (re-find #"case ui_WidgetNode_(\w+)_tag:" line))) acc]

        (re-find #"!morph_in_progress \|\| p->(\w+) != 0" line)
        [arm (if arm
               (update acc arm (fnil conj #{})
                       (keyword (second (re-find #"!morph_in_progress \|\| p->(\w+) != 0" line))))
               acc)]

        :else [arm acc]))
    [nil {}]
    (str/split-lines src))))

(deftest every-morph-guard-in-the-c-has-a-table-entry
  (let [guards (morph-guarded-fields (slurp renderer-c))]
    ;; NON-VACUITY FIRST. If the regex stops matching — a reformat, a renamed flag —
    ;; the comparison below succeeds over an empty set and reports a perfect score
    ;; for having read nothing. That is the vacuous pass
    ;; `.claude/rules/gate-enforcement.md` §3 forbids, and it is the likely way this
    ;; test dies.
    (testing "the C parse found guards at all"
      (is (seq guards) "no morph guards parsed from renderer.c — the pattern stopped matching")
      (is (>= (count (mapcat val guards)) 4)
          "fewer morph guards than the four known to exist; the parse is partial"))
    (testing "every morph-guarded field is declared a one-way door"
      (doseq [[arm fields] guards
              field fields]
        (is (contains? (get patch/guarded-arm-fields arm #{}) field)
            (format "renderer.c guards %s/%s behind `0 means keep`, but guarded-arm-fields omits it — a regression of that field to 0 would be classified UPDATE_PROPS and the renderer would SKIP applying it"
                    (name arm) (name field)))))))

(deftest the-three-value-fields-are-declared
  ;; The specific regression, spelled out so the diff is legible even if the derived
  ;; test above is ever narrowed. slider/arc/spinbox each carry a morph value guard.
  (testing "slider, arc and spinbox declare :value"
    (doseq [arm [:slider_props :arc_props :spinbox_props]]
      (is (contains? (get patch/guarded-arm-fields arm) :value)
          (str (name arm) " must declare :value")))))

(deftest bar-value-is-NOT-declared-and-that-is-correct
  ;; THE ASYMMETRY IS THE C SOURCE'S. `lv_bar_set_value` is applied
  ;; UNCONDITIONALLY, so a bar value is morphable at every transition and listing it
  ;; would force needless REPLACE_NODEs. Only `:start_value` is guarded. This test
  ;; exists so a future reader "making the table consistent" meets a red instead of
  ;; a plausible-looking edit.
  (testing "bar declares :start_value but not :value"
    (is (contains? (get patch/guarded-arm-fields :bar_props) :start_value))
    (is (not (contains? (get patch/guarded-arm-fields :bar_props) :value))
        "bar's value is applied unconditionally in renderer.c; declaring it here would force needless replacements"))
  (testing "and the C agrees — bar carries no morph value guard"
    (is (not (contains? (get (morph-guarded-fields (slurp renderer-c)) :bar_props #{}) :value)))))

(deftest the-table-declares-nothing-the-c-does-not-guard-in-the-morph-class
  ;; The other direction, reported rather than asserted: a table entry with no C
  ;; guard at all would force replacements for nothing. It cannot be a hard failure
  ;; here because the table legitimately also covers the plain `if (p->F != 0)`
  ;; guards this parse does not read — so this states the boundary instead of
  ;; pretending to totality it does not have.
  (testing "every declared arm key is a real widget_props arm"
    (let [guards (morph-guarded-fields (slurp renderer-c))
          declared (set (keys patch/guarded-arm-fields))]
      (is (empty? (set/difference (set (keys guards)) declared))
          "renderer.c morph-guards an arm that guarded-arm-fields does not mention at all"))))

(defn observer-attaching-fields
  "Parse `renderer.c` for the `node->has_FIELD && … pending_queue_has_room(`
  guards in `finalize_widget` and return `#{:field …}` — the node fields whose
  presence queues an attach that ends in a subject bind or an observer.

  Whitespace is collapsed first because the guard wraps across lines. The
  pairing is what the C's own structure gives: every queued attach is written
  as one `if` whose head names the field it reads."
  [src]
  (into #{}
        (map (comp keyword second))
        (re-seq #"if \(node->has_(\w+) &&[^{]*?pending_queue_has_room\("
                (str/replace src #"\s+" " "))))

(deftest every-observer-attaching-field-is-stripped-from-update-payloads
  (let [fields (observer-attaching-fields (slurp renderer-c))]
    ;; NON-VACUITY FIRST, for the reason the morph-guard test above gives: an
    ;; empty parse compares clean and reports a perfect score for having read
    ;; nothing (`.claude/rules/gate-enforcement.md` §3).
    (testing "the C parse found queued attaches at all"
      (is (seq fields)
          "no queued observer attaches parsed from renderer.c — the pattern stopped matching")
      (is (>= (count fields) 5)
          "fewer queued attaches than the five known to exist; the parse is partial"))
    (testing "every one is a morph invariant, so an UNCHANGED binding never rides an UPDATE payload back into the attach path"
      (doseq [f fields]
        (is (contains? patch/morph-invariant-keys f)
            (format "renderer.c queues an observer attach for node->has_%s, but morph-invariant-keys omits it — an UPDATE_PROPS morph would carry it into finalize_widget against a LIVE object, where the only morph guard covers :event alone; neither the drain nor apply_compare_binding deduplicates, and nothing detaches, so every morph adds another observer"
                    (name f)))))))

(deftest enabled-when-and-color-when-are-stripped-and-force-replace
  ;; THE SPECIFIC REGRESSION, spelled out so the diff stays legible if the derived
  ;; test above is ever narrowed. These two sat in NEITHER key set, so they rode
  ;; EVERY UPDATE payload — not merely a changed one — into a duplicate attach.
  ;; Both memberships are load-bearing and they are not interchangeable: the
  ;; morph-invariant half strips an unchanged binding, the replace-on-change half
  ;; sends a changed one to a fresh object.
  (doseq [k [:enabled_when :color_when]]
    (testing (name k)
      (is (contains? patch/morph-invariant-keys k)
          (str (name k) " must be stripped from UPDATE payloads"))
      (is (contains? patch/replace-on-change-keys k)
          (str (name k) " must force REPLACE when it changes")))))
