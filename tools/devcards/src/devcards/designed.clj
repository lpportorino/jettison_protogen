(ns devcards.designed
  "Loads `corpus/designed-flags.edn` — the per-card, per-family declarations of
   which layout-defect flags are a card's SUBJECT rather than its defect.

   Separate from `devcards.fixtures` because both corpus lanes read it: the
   atomic build and the composition build. Putting it in either one would make
   the other require a namespace whose job it does not share, and `fixtures`
   already loads the generated protobuf bindings — which is exactly what keeps
   it out of the `:test` alias (see `devcards.lanes`' own ns docstring for the
   cost of that). This namespace loads nothing but EDN, so a canary can name it.

   The SHAPE of an entry, and why its proof is two keys instead of the four a
   waiver owes, lives on `devcards.invariants/designed-flag-keys`; the
   validation runs at the seam that reads it
   (`invariants/apply-designed-flags`), so a malformed entry names the card and
   the clause rather than failing here as an anonymous read error."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(set! *warn-on-reflection* true)

(def declarations-path
  "Tool-relative, like `fixtures/spec-path`. Protogen home:
   tools/devcards/corpus/."
  "corpus/designed-flags.edn")

(def ^:private file-keys #{:doc :cards})

(defn load-declarations
  "Read and hand-validate the declaration file. Returns {card-id [entry ...]}.

   REFUSES A MISSING FILE rather than defaulting to {}. An absent file and a
   file declaring nothing are the same value to every caller downstream, and
   the second is a claim while the first is an accident — a checkout that lost
   this file would start reporting every declared finding as a fresh defect with
   no hint that a declaration file had ever existed. That is more findings than
   the file has ENTRIES, since an entry is matched once per family it names and
   may match several nodes; the count is not written here because it moves with
   the corpus. A corpus with nothing to declare commits
   `{:doc \"...\" :cards {}}`.

   It does NOT validate the entries. That is deliberate and not laziness: the
   entry shape is `devcards.invariants`' contract, and validating it twice would
   put a second copy of that shape here, free to disagree. What this checks is
   only what it owns — the file's own envelope."
  ([] (load-declarations declarations-path))
  ([path]
   (let [f (io/file path)]
     (when-not (.exists f)
       (throw (ex-info (str "missing designed-flag declarations: " path
                            " — an absent file is not the same claim as an"
                            " empty :cards map, so it is refused rather than"
                            " defaulted")
                       {:path path})))
     (let [m (edn/read-string (slurp f))]
       (when-not (map? m)
         (throw (ex-info (str path ": expected a map") {:path path})))
       (when-let [extra (seq (remove file-keys (keys m)))]
         (throw (ex-info (str path ": unknown top-level keys (closed shape)")
                         {:path path :unknown (vec extra)})))
       (let [cards (:cards m)]
         (when-not (map? cards)
           (throw (ex-info (str path ": :cards must be a map of card id -> entries")
                           {:path path :got (type cards)})))
         (when-let [bad (seq (remove string? (keys cards)))]
           (throw (ex-info (str path ": :cards keys must be card id strings")
                           {:path path :bad (vec bad)})))
         cards)))))

(defn unknown-card-ids
  "The declaration keys that name no card in `card-ids`.

   THE STALE CLAUSE CANNOT SEE THESE, which is why this exists. That clause asks
   \"did this entry match a finding on a family it names\" and it is only ever
   asked about cards the run actually judged — so an entry filed under a
   MISSPELLED id is never consulted, never matched, and never reported stale. It
   is dead and invisible at once, which is the same defeat-by-one-keystroke that
   `:stale-designed-flag` exists to prevent.

   BOTH SIDES ARE STRINGS BY CONSTRUCTION and this fn does not normalise them:
   `load-declarations` refuses a non-string key, and every corpus id is a string
   in `spec.edn` and `composition.edn` alike. A `str` call here would be a
   normalisation no input can exercise, which reads as tolerance for a shape
   that would in fact silently never match.

   Returns a sorted vector so a failure names the ids in a stable order. The
   CALLER turns it into a finding: this namespace loads and answers questions,
   it does not decide verdicts."
  [declarations card-ids]
  (vec (sort (remove (set card-ids) (keys declarations)))))

(defn for-card
  "The declaration entries for one card id, or nil when it declares none.

   nil rather than [] on purpose: `invariants/validate-designed-flags!` refuses
   an EMPTY vector — `[]` and \"declares nothing\" are indistinguishable, so a
   card that meant to declare something and typo'd its id would read as one
   that declared nothing. nil is the honest spelling of absence."
  [declarations card-id]
  (get declarations (str card-id)))
