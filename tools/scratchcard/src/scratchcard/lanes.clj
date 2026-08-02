(ns scratchcard.lanes
  "Judges a scratch render with THIS REPO'S OWN armed quality lanes.

  NOTHING HERE IS A SECOND IMPLEMENTATION. The producers, the thresholds, the
  classification table and the exemption list are all read from
  `devcards.lanes` — the same values the corpus gate runs. A quality rule that
  lived only in this tool would fork the standard: two surfaces required to
  agree would disagree about whether the same screen is defective, and the
  defect one of them caught would stay live in the other.

  THE ONE ADDITION, and it is an addition rather than a change: the `:emission`
  builtin joins the armed atomic set. A scratch render captures the host
  emission lanes (`host_command`, `host_report`, `host_event`,
  `host_proxy_report`) that the atomic corpus lane does not consult, and an
  authored screen firing an unexpected host command is exactly the class a
  scratch iteration wants to see immediately. It is added THROUGH the registry
  — `:producers` is a parameter — never by editing a lane.

  WHY EVERY CELL IS JUDGED, not just the dark one. The corpus dumps the tree
  for dark renders only and hashes the rest. That is a defensible economy for
  a 1,400-render gate; it is the wrong trade for a tool whose whole job is to
  show an author what their screen does, because a light-mode-only defect
  would then be invisible in the surface built to find it.

  THREE ANSWERS, ALWAYS. `judge` reports findings, cleanliness, AND what it
  could not look at. A lane that passes over what it could not classify
  reports \"clean\" and \"I could not look\" as the same empty vector, and this
  namespace refuses to.

  WHAT IS NOT ARMED, AND WHY — recorded rather than forgotten, and surfaced in
  the manifest as `:declined` so a reader never mistakes silence for coverage:
    :layers  — its declaration is uid-keyed, and renderer-built affordances
               are permanently uid-free, so it can judge authored nodes and
               never a proxy's internals.
    :palette — its `:draw-palette` context key is not in the registry's closed
               `context-keys`, so the registry would refuse the call.
    :border  — its thresholds are per-TARGET contour declarations, which a
               scratch screen does not carry."
  (:require
   [clojure.string :as str]
   [devcards.findings :as findings]
   [devcards.lanes :as dlanes]
   [scratchcard.digest :as digest]))

(def declined
  "Shipped producers this lane deliberately does not arm. See the ns docstring."
  [:layers :palette :border])

(def producers
  "protogen's armed atomic set, plus the emission builtin.

  Derived from `devcards.lanes/atomic-producers` rather than restated, so an
  addition to the corpus gate reaches this tool automatically."
  (conj dlanes/atomic-producers (findings/builtin-producer :emission)))

(def thresholds
  "Read from the gate, never re-spelled. An unknown key throws in the registry
  rather than falling back, so a typo here could not quietly relax a gate —
  but a COPIED value could silently differ from the one being enforced."
  dlanes/producer-thresholds)

(def classes
  "The shipped LVGL classification table, unextended.

  A consumer merges its own table OVER this one; a scratch card in THIS repo
  has no private widgets, so it takes the shipped table as-is."
  dlanes/overlap-classes)

(def caps
  "Capability declaration for the loaded module.

  MUST be supplied. Defaulting it once silently deleted the entire
  `:zero-visible-area` class — the registry treats an absent capability as
  'not declared' and the clause simply stops running, with output identical to
  a clean run."
  {:vis-px? true})

(defn classes-sha
  "A digest of the classification table actually used.

  Rides the manifest so a run-to-run diff can attribute a change to the
  JUDGEMENT rather than to the renderer. Without it, editing the table looks
  exactly like a pixel regression."
  ^String []
  (digest/of-string (pr-str (into (sorted-map) (:types classes)))))

(defn descriptor
  "The `:lanes` manifest block: what judged this run, and how."
  []
  {:producers (mapv :id producers)
   :thresholds thresholds
   :caps caps
   :classes-sha (classes-sha)
   :declined declined})

(defn judge
  "Findings for ONE rendered cell.

  `tree` is the parsed `dump_tree`, `emissions` the captured host lanes.
  `family` is REQUIRED and is not defaulted: a family defaulted to 0 would
  judge every render as though it were the shipped theme, which is precisely
  the blindness that makes a theme-specific defect invisible.

  Returns the registry's own map — `:live`, `:exempted`, `:stale-exemptions` —
  rather than only `:live`. The corpus gate drops the other two; a tool
  reporting to a human-or-model reader must not, because an exemption that
  matched nothing is itself a finding.

  EVERY FINDING IS STAMPED WITH ITS CELL AND FAMILY HERE, and that is not
  belt-and-braces. Measured while wiring this: only the DOM producer stamps
  `:family` — `overlap`, `deadzone` and `opa` emit findings without it. That
  is harmless for the corpus gate, which judges one family per call and
  reports per card. It is NOT harmless here: this tool renders six theme
  states across many canvases into one findings vector, so an unstamped
  finding could not be attributed to the render that produced it. The stamp
  belongs at the only layer that knows the cell, which is this one."
  [{:keys [card-id tree emissions family expect cell]}]
  (let [result (findings/card-findings
                {:card-id (str card-id)
                 :tree tree
                 :caps caps
                 ;; The registry treats nil as ABSENT and would refuse the
                 ;; call, so this must be a real value. `:judged` is the
                 ;; honest default for an authored screen: it declares
                 ;; nothing and asks for the full lane.
                 :expect (or expect :judged)
                 :family family
                 ;; A scratch screen declares no designed defects.
                 ;; Supplied-but-empty is a CLAIM ("this screen concedes
                 ;; nothing"), which is different from absent.
                 :declaration {:designed-flags nil}
                 :emissions (or emissions {})
                 :host-proxy? false
                 :classes classes
                 :thresholds thresholds
                 :producers producers
                 :armed-producers producers
                 :exemptions dlanes/gate-exemptions})
        stamp (fn [fs] (mapv #(cond-> (assoc % :family family)
                                cell (assoc :cell cell))
                             fs))]
    (-> result
        (update :live stamp)
        (update :exempted stamp)
        (update :stale-exemptions stamp))))

(defn- unjudged-findings
  "The THIRD ANSWER: nodes the lane could not classify.

  `devcards.classify` already emits `:unclassified-type` per distinct type, so
  this only counts them out for the summary. It exists so a caller cannot
  print a clean verdict over a tree the rule declined to judge."
  [live]
  (filterv #(contains? #{:unclassified-type :unmeasurable-node :dump-truncated}
                       (:invariant %))
           live))

(defn summarise
  "Fold per-cell findings into the manifest's `:findings` block.

  Reports the count, the breakdown by invariant, and — separately — how many
  findings mean COULD NOT LOOK rather than FOUND SOMETHING. Collapsing those
  two into one number is how a gate reports clean over what it never judged."
  [cell-results]
  (let [live (into [] (mapcat :live) cell-results)
        stale (into [] (mapcat :stale-exemptions) cell-results)
        unjudged (unjudged-findings live)]
    {:count (count live)
     :by-invariant (into (sorted-map) (frequencies (map :invariant live)))
     :by-cell (into (sorted-map)
                    (for [{:keys [cell live]} cell-results
                          :when (seq live)]
                      [cell (count live)]))
     :unjudged (count unjudged)
     :stale-exemptions (count stale)
     :clean? (and (zero? (count live)) (zero? (count stale)))}))

(defn describe
  "A one-line human summary of a `summarise` result.

  Destructures to `n-findings` rather than `count`: binding `count` would
  shadow `clojure.core/count`, which this repo has been bitten by twice — the
  linter stays green and a missed reference dies at runtime."
  ^String [{:keys [unjudged clean? by-invariant] n-findings :count}]
  (if clean?
    "lanes: clean"
    (str "lanes: " n-findings " finding(s) — "
         (str/join ", " (map (fn [[k v]] (str (name k) " x" v)) by-invariant))
         (when (pos? (long unjudged))
           (str "; " unjudged " of these mean COULD-NOT-JUDGE, not a defect")))))
