(ns protodoc.lint
  "Lint proto documentation for quality issues beyond simple coverage.

   Each rule is a function (db) -> [finding ...] where finding is:
   {:rule     :rule-keyword
    :severity :error | :warning | :info
    :id       \"message.or.enum.id\"
    :field    \"field_name\" or nil
    :message  \"Human-readable issue description\"}"
  (:require [clojure.string :as str]
            [protodoc.schema :as schema]))

;; ============================================================================
;; Semantic type / field type compatibility matrix
;; ============================================================================

(def semantic-type-compatible-field-types
  "Map of semantic-type -> set of compatible proto field types.
   nil means compatible with all field types."
  {:normalized       #{:double :float}
   :angle            #{:double :float :int32 :uint32}
   :cardinal         #{:double :float :int32 :uint32}
   :percentage       #{:double :float :int32 :uint32}
   :coordinate-geo   #{:double :float}
   :coordinate-viewport #{:double :float}
   :speed            #{:double :float}
   :voltage          #{:double :float}
   :current          #{:double :float}
   :power            #{:double :float}
   :temperature      #{:double :float :int32}
   :distance         #{:double :float :uint32 :int32 :uint64 :int64}
   :duration         #{:uint32 :uint64 :int32 :int64 :double :float}
   :count            #{:uint32 :int32 :uint64 :int64}
   :timestamp        #{:uint64 :int64 :uint32 :double}
   :identifier       #{:uint32 :int32 :uint64 :int64 :string}
   :enum-label       #{:enum}
   :toggle-state     #{:bool}
   :raw              nil})

;; ============================================================================
;; Percent-display multiplier
;; ============================================================================

(def ^:private percent-multiplier-pattern
  "Matches a display-format that scales the stored value by 100.

   The negative lookahead is the whole point: `* 1000` is a DIFFERENT multiplier
   (seconds to milliseconds, say) and must not be swept in, while `* 100.0` is
   the same one written differently and must be. Whitespace around the operator
   is optional because nothing normalises the authored string."
  #"\*\s*100(?![0-9])")

;; ============================================================================
;; Vague description patterns
;; ============================================================================

(def ^:private vague-patterns
  "Regex patterns that indicate a vague or placeholder description."
  [#"(?i)\bunclear\b"
   #"(?i)\bTBD\b"
   #"(?i)\bTODO\b"
   #"(?i)\bunknown\b"
   #"\?\?"])

;; ============================================================================
;; Individual lint rules
;; ============================================================================

(defn- enum-values-undocumented
  "Enum values with nil/empty/'-' descriptions when parent enum has a description."
  [db]
  (for [[_id enum] (:enums db)
        :when (seq (:description enum))
        :let [values (:values enum)
              total (count values)
              undoc (count (filter (fn [v]
                                     (let [d (:description v)]
                                       (or (nil? d) (str/blank? d) (= d "-"))))
                                   values))]
        :when (and (pos? total) (pos? undoc))]
    {:rule     :enum-values-undocumented
     :severity :warning
     :id       (:id enum)
     :field    nil
     :message  (format "%d/%d values undocumented" undoc total)}))

(defn- field-metadata-without-description
  "Fields with :interaction metadata but no text description."
  [db]
  (for [[_id msg] (:messages db)
        field (:fields msg)
        :when (and (seq (:interaction field))
                   (not (seq (:description field))))]
    {:rule     :field-metadata-without-description
     :severity :warning
     :id       (:id msg)
     :field    (:name field)
     :message  (format "field '%s' has interaction metadata but no description"
                       (:name field))}))

(defn- semantic-type-mismatch
  "Incompatible semantic-type + field-type combinations."
  [db]
  (for [[_id msg] (:messages db)
        field (:fields msg)
        :let [sem-type (get-in field [:interaction :semantic-type])
              field-type (:type field)]
        :when sem-type
        :let [compatible (get semantic-type-compatible-field-types sem-type)]
        :when (and compatible (not (contains? compatible field-type)))]
    {:rule     :semantic-type-mismatch
     :severity :error
     :id       (:id msg)
     :field    (:name field)
     :message  (format "field '%s': %s incompatible with %s"
                       (:name field) (name field-type) (name sem-type))}))

(defn- interaction-incomplete
  "Messages with :category but missing :ui-pattern or :feedback.

   THE GUARD IS :category, SO A BLOCK CARRYING NO :category IS OUTSIDE THIS
   RULE ENTIRELY — unjudged, not judged and found complete. A message whose
   :interaction holds only :preconditions reports here exactly what a clean
   message reports, which is nothing. Derive that population rather than
   assuming it is empty:

     grep -L 'Category' $(grep -l '^## Interaction' docs/proto/*.md)

   WIDENING THE GUARD TO :interaction IS DECLINED, because the only remedy it
   would leave is a fabrication. This rule demands :ui-pattern and :feedback
   beside :category, and for the pages that currently qualify the sole
   available source for all three is the opposite camera's twin page. Those
   twin values come from one bulk metadata extraction that assigned the two
   cameras OPPOSITE categories on four twin commands, so it is measurably
   unreliable for the very field that would be copied. An invented :category
   is an invented exemption :rationale one layer earlier, and a warning here
   fails the command outright, so widening would force that invention rather
   than merely inviting it.

   RETIRES WHEN interaction metadata has a source that is not that
   extraction — a device contract, a per-command determination, or an
   authored page. The guard becomes :interaction then, and the pages the
   derivation prints are written rather than waived."
  [db]
  (for [[_id msg] (:messages db)
        :let [inter (:interaction msg)]
        :when (:category inter)
        :let [missing (cond-> []
                        (not (:ui-pattern inter)) (conj ":ui-pattern")
                        (not (:feedback inter))   (conj ":feedback"))]
        :when (seq missing)]
    {:rule     :interaction-incomplete
     :severity :warning
     :id       (:id msg)
     :field    nil
     :message  (str "has :category but missing " (str/join ", " missing))}))

(defn- invalid-references
  "Related-state/related-commands pointing to non-existent IDs.

   A `:related-state` entry may name a field (`<message>#<field>`), so this
   clause resolves its MESSAGE half only. The field half belongs to
   `:unresolved-state-field`; reporting it from both would make one defect look
   like two, and would leave neither rule attributable on its own."
  [db]
  (let [all-ids (into (set (keys (:messages db)))
                      (keys (:enums db)))]
    (for [[_id msg] (:messages db)
          :let [inter (:interaction msg)]
          :when inter
          ref-str (concat (:related-state inter) (:related-commands inter))
          :let [target-id (first (schema/split-state-ref ref-str))]
          :when (not (contains? all-ids target-id))]
      {:rule     :invalid-references
       :severity :error
       :id       (:id msg)
       :field    nil
       :message  (format "reference '%s' not found in database" ref-str)})))

(defn- unresolved-state-field
  "A `:related-state` reference naming a field its target does not declare.

   WHY THE FIELD GRAIN NEEDS ITS OWN CLAUSE. A message-grained pointer survives
   almost any drift, because a message keeps existing while its fields are
   renamed underneath it — which is exactly why nothing caught the imprecision
   the field grain exists to remove. Once a pointer names a field, a rename makes
   it FALSE, and only a resolver notices.

   An unknown TARGET is deliberately silent here: `:invalid-references` already
   names it, and a message that does not exist cannot be asked for its fields.
   An ENUM target with a field suffix is this clause's, not that one's — the id
   resolves, so the other rule is satisfied, and the reference is still
   unanswerable."
  [db]
  (for [[_id msg] (:messages db)
        ref-str (get-in msg [:interaction :related-state])
        :let [[target-id field-name] (schema/split-state-ref ref-str)]
        :when field-name
        :let [target-msg (get-in db [:messages target-id])
              enum-target? (contains? (:enums db) target-id)]
        :when (or target-msg enum-target?)
        :when (or enum-target?
                  (not-any? #(= field-name (:name %)) (:fields target-msg)))]
    {:rule     :unresolved-state-field
     :severity :error
     :id       (:id msg)
     :field    nil
     :message  (if enum-target?
                 (format "related-state '%s' names a field, but '%s' is an enum and has none"
                         ref-str target-id)
                 (format "related-state '%s': message '%s' declares no field '%s'"
                         ref-str target-id field-name))}))

(defn- description-vague
  "Descriptions containing placeholder/vague text."
  [db]
  (let [check-desc (fn [id desc]
                     (when (seq desc)
                       (some (fn [pat]
                               (when (re-find pat desc)
                                 {:rule     :description-vague
                                  :severity :info
                                  :id       id
                                  :field    nil
                                  :message  (format "description contains vague text matching %s" pat)}))
                             vague-patterns)))]
    (concat
     ;; Check message descriptions
     (keep (fn [[_id msg]] (check-desc (:id msg) (:description msg)))
           (:messages db))
     ;; Check enum descriptions
     (keep (fn [[_id enum]] (check-desc (:id enum) (:description enum)))
           (:enums db)))))

(defn- constrained-fields-undocumented
  "Fields with validation constraints but no description."
  [db]
  (for [[_id msg] (:messages db)
        field (:fields msg)
        :when (and (seq (:constraints field))
                   (not (seq (:description field))))]
    {:rule     :constrained-fields-undocumented
     :severity :warning
     :id       (:id msg)
     :field    (:name field)
     :message  (format "field '%s' has constraints %s but no description"
                       (:name field) (pr-str (:constraints field)))}))

(defn- percent-display-precision
  "Fields whose display-format scales by 100 but do not declare precision 0.

   THE CONVENTION IS ALREADY WRITTEN — README.md presents `:normalized` / `%` /
   precision 0 / `{value * 100}%` as the canonical field-level interaction shape.
   Nothing enforced it. `:semantic-type-mismatch` compares a semantic type
   against the field TYPE and `:interaction-incomplete` reads message-level keys,
   so neither could see a percent display carrying the wrong precision, and
   neither can see it come back.

   WHY THE PREDICATE IS THE DISPLAY SHAPE AND NOT THE SEMANTIC TYPE. Grouping by
   `:normalized` mixes fields the consumer renders as a raw fraction with fields
   it renders as a percent, and those two want different precisions. A `* 100`
   multiplier says outright that the displayed number is a percent of a fraction,
   and a percent of a fraction is displayed in whole units. Group by display
   shape and the population answers unanimously.

   AN ABSENT PRECISION IS A FINDING TOO. The convention is that this display's
   precision is 0 AND that it is stated: leave the key out and every consumer
   picks its own default, which is the same drift arriving by omission."
  [db]
  (for [[_id msg] (:messages db)
        field (:fields msg)
        :let [inter (:interaction field)
              fmt (:display-format inter)]
        :when (and fmt (re-find percent-multiplier-pattern fmt))
        :let [precision (:precision inter)]
        :when (not= 0 precision)]
    {:rule     :percent-display-precision
     :severity :error
     :id       (:id msg)
     :field    (:name field)
     :message  (format "field '%s': display-format \"%s\" scales by 100, so precision must be 0 — %s"
                       (:name field) fmt
                       (if (nil? precision)
                         "none declared"
                         (str "found " precision)))}))

;; ============================================================================
;; Rule registry and orchestrator
;; ============================================================================

(def ^:private display-format-suffix-pattern
  "Literal text trailing the LAST `{...}` placeholder in a display-format."
  #"\}([^{}]*)$")

(defn- display-format-unit-suffix
  "The trimmed literal a display-format prints AFTER its last placeholder, or nil
   when the format carries no placeholder at all.

   THE NO-FALSE-POSITIVE CLAIM IS NARROWER THAN IT SOUNDS, so it is stated
   precisely: no POPULATION-LEVEL judgement enters, because both keys belong to
   one field. That is not the same as the predicate being unconditionally safe.
   It compares the trimmed literal against the unit, and `str/trim` strips
   Unicode whitespace via `Character/isWhitespace` — which by definition EXCLUDES
   the non-breaking spaces. Measured: U+2009, U+2000, U+205F, U+1680 and U+3000
   are all above U+0020 and ARE stripped, while U+00A0, U+2007 and U+202F survive
   and read as part of the spelling. U+202F is a standard unit separator, so that
   is the realistic case rather than a contrived one; the value of the codepoint
   decides nothing.
   A decorated suffix does the same: `{value} m (AGL)` prints `m (AGL)`, which
   is a correct format against a correct unit. This corpus carries neither today
   (zero NBSP), so the rule is clean here rather than clean by construction.

   THE NO-PLACEHOLDER CASE IS NOT A DEGENERATE ONE, IT IS LIVE. The corpus
   carries at least one display-format that is a standing LABEL rather than a
   value template. Such a string prints no value, so it declares no unit, and
   taking the text after a non-existent brace would hand the whole label back as
   a spelling — a guaranteed false finding against a field that is not wrong.
   Returning nil is what keeps the rule below incapable of a false positive."
  [fmt]
  (when-let [m (re-find display-format-suffix-pattern fmt)]
    (let [suffix (str/trim (second m))]
      (when (seq suffix) suffix))))

(defn- unit-contradicts-display-format
  "Fields whose declared unit disagrees with the unit their own display-format
   prints.

   PER-FIELD, NO GROUPING, NO VOCABULARY. Both keys belong to ONE field, so the
   field disagrees with itself and no corpus-wide judgement is involved. That is
   what makes it free of false positives in a tool with no allowlist: there is no
   population whose members might legitimately differ.

   IT IS A CONSISTENCY RULE, NOT A WRONG-UNIT-ON-SCREEN RULE, and the difference
   is worth stating because the stronger claim is the tempting one. Every finding
   it produces is ONE QUANTITY SPELLED TWO WAYS. A consumer's display-unit
   prefers the unit key where present, so a mismatch here puts a
   correct-but-differently-spelled label on screen, never a false quantity. Do
   not sell it as an accuracy gate.

   RESOLVE TOWARD WHAT THE FIELD PRINTS. The display-format is the half a reader
   actually sees, so it decides; the precedent is the hand-fix that resolved
   meters -> m on exactly that ground rather than by counting the corpus."
  [db]
  (for [[_id msg] (:messages db)
        field (:fields msg)
        :let [inter (:interaction field)
              unit (:unit inter)
              fmt (:display-format inter)]
        :when (and unit fmt)
        :let [suffix (display-format-unit-suffix fmt)]
        :when (and suffix (not= suffix unit))]
    {:rule     :unit-contradicts-display-format
     :severity :error
     :id       (:id msg)
     :field    (:name field)
     :message  (format "field '%s': declares :unit \"%s\" but its display-format \"%s\" prints \"%s\" — one quantity spelled two ways; make the two keys agree"
                       (:name field) unit fmt suffix)}))

(defn- unit-bearing-fields
  "Every field declaring a unit, flattened with its description and the literal
   its own display-format prints (nil when it declares no value template)."
  [db]
  (for [[_id msg] (:messages db)
        field (:fields msg)
        :let [inter (:interaction field)]
        :when (:unit inter)]
    {:id     (:id msg)
     :name   (:name field)
     :desc   (:description field)
     :unit   (:unit inter)
     :prints (some-> (:display-format inter) display-format-unit-suffix)}))

(defn- unit-disagrees-across-identical-descriptions
  "Fields sharing a byte-identical description whose declared units disagree AND
   whose own display-formats settle which spelling is right.

   BYTE-IDENTICAL DESCRIPTION IS NOT SUFFICIENT ON ITS OWN, and believing it was
   is what made an earlier draft of this rule fire on CORRECT data. The tempting
   justification — two fields described identically are the same quantity by the
   corpus's own statement — fails whenever the description is BOILERPLATE. The
   live counter-example: nine fields share one generic step-offset description
   across commands that step days, hours, minutes, months, seconds, timezones and
   years; two of them declare a unit, they disagree, and BOTH ARE RIGHT. The
   quantity lives in the message name, which this key cannot see.

   SO THE GROUP MUST ARBITRATE ITSELF, and the display-format is how. Fire only
   when the format evidence (a) is carried by fields on MORE THAN ONE SIDE of the
   unit split and (b) agrees on one spelling.

   (a) IS THE CLAUSE THAT DOES THE WORK, and dropping it looks harmless. Without
   it, a group whose single format sits on the DISPUTED field arbitrates in that
   field's own favour — and the one genuine cross-field quantity dispute in this
   corpus is exactly that shape, a lone microsecond field among nanosecond
   siblings, carrying the only template. Requiring the evidence to CROSS the
   split is what stops the rule asserting the wrong answer to the one question it
   cannot answer.

   (b) is what lets the finding name a RESOLUTION rather than only reporting
   disagreement, which matters in a tool with no allowlist: a finding that cannot
   be acted on can only be silenced by deleting the rule.

   IT IS SUBORDINATE TO THE PER-FIELD RULE, which makes it a ONE-SHOT CLEANUP
   rule rather than a standing guard, and that is worth knowing before relying on
   it. Its arbiters are exactly the fields carrying both keys — and in a corpus
   where the per-field rule is green, every such field already has unit equal to
   what it prints. Then all arbiters agree with their own units, the spelling set
   collapses to one, and the more-than-one-side clause can never be satisfied.
   Since this lint is blocking with no allowlist, per-field-green IS the enforced
   steady state, so after the initial cleanup this rule cannot fire again.

   IT DELIBERATELY UNDER-REPORTS. A group whose formats all sit on one side is
   refused even when the disagreement is a real spelling split, because nothing
   in the corpus settles it — that is human judgement, and a rule widened until
   it covers those cases is a rule firing on the generic-description group again."
  [db]
  (for [[desc group] (group-by :desc (unit-bearing-fields db))
        :when (and desc (< 1 (count (distinct (map :unit group)))))
        :let [arbiters  (filter :prints group)
              sides     (distinct (map :unit arbiters))
              spellings (distinct (map :prints arbiters))]
        :when (and (< 1 (count sides)) (= 1 (count spellings)))
        :let [answer (first spellings)]
        f group
        :when (not= (:unit f) answer)]
    {:rule     :unit-disagrees-across-identical-descriptions
     :severity :error
     :id       (:id f)
     :field    (:name f)
     :message  (format "field '%s': declares :unit \"%s\" while fields described identically (\"%s\") print \"%s\" — their own display-formats agree across the split, so \"%s\" is the spelling"
                       (:name f) (:unit f) desc answer answer)}))

(def default-rules
  "Registry of all lint rules."
  {:enum-values-undocumented        enum-values-undocumented
   :field-metadata-without-description field-metadata-without-description
   :semantic-type-mismatch          semantic-type-mismatch
   :interaction-incomplete          interaction-incomplete
   :invalid-references              invalid-references
   :unresolved-state-field          unresolved-state-field
   :description-vague               description-vague
   :constrained-fields-undocumented constrained-fields-undocumented
   :percent-display-precision       percent-display-precision
   :unit-contradicts-display-format unit-contradicts-display-format
   :unit-disagrees-across-identical-descriptions unit-disagrees-across-identical-descriptions})

(defn lint
  "Run lint rules against a proto database.

   Options:
   - :rules    - set of rule keywords to run (default: all)
   - :exclude  - set of rule keywords to skip (default: none)
   - :severity - minimum severity to include, #{:error :warning :info} (default: all)

   Returns {:findings [...] :summary {:error N :warning N :info N}}"
  [db & {:keys [rules exclude severity]}]
  (let [active-rules (cond-> default-rules
                       rules   (select-keys rules)
                       exclude (#(apply dissoc % exclude)))
        all-findings (->> (vals active-rules)
                          (mapcat #(% db))
                          (sort-by (juxt (comp {:error 0 :warning 1 :info 2} :severity)
                                         :rule :id :field)))
        filtered (if severity
                   (filter #(contains? severity (:severity %)) all-findings)
                   all-findings)
        summary (merge {:error 0 :warning 0 :info 0}
                       (update-vals (group-by :severity filtered) count))]
    {:findings (vec filtered)
     :summary summary}))

(defn format-findings
  "Format lint results as a human-readable report string."
  [{:keys [findings summary]}]
  (let [sb (StringBuilder.)
        _ (.append sb "Proto Documentation Lint Report\n")
        _ (.append sb "================================\n")
        grouped-by-sev (group-by :severity findings)
        rules-checked (count default-rules)]
    (doseq [[sev label] [[:error "ERRORS"] [:warning "WARNINGS"] [:info "INFO"]]]
      (when-let [sev-findings (seq (get grouped-by-sev sev))]
        (.append sb (format "\n%s (%d)\n" label (count sev-findings)))
        (let [by-rule (group-by :rule sev-findings)]
          (doseq [[rule rule-findings] (sort-by key by-rule)]
            (.append sb (format "  %s (%d)\n" rule (count rule-findings)))
            (doseq [f (sort-by (juxt :id :field) rule-findings)]
              (if (:field f)
                (.append sb (format "    %s #%s: %s\n" (:id f) (:field f) (:message f)))
                (.append sb (format "    %s: %s\n" (:id f) (:message f)))))))))
    (when (empty? findings)
      (.append sb "\nNo issues found.\n"))
    (.append sb (format "\nSummary: %d errors, %d warnings, %d info (%d rules checked)\n"
                        (:error summary) (:warning summary) (:info summary) rules-checked))
    (str sb)))
