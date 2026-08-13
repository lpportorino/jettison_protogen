(ns devcards.spec-check
  "PURE validators over the parsed corpus spec — no IO, no protobuf.

   WHY THIS IS NOT IN `devcards.fixtures`, WHICH OWNS THE SPEC. That namespace
   imports the generated `ui.UiAst` classes at its ns form, and those bindings
   are deliberately absent from the `:test` classpath — `devcards.lanes-test`
   says so outright, and `devcards.state-mirror-test` reads fixtures.clj as TEXT
   rather than requiring it, for exactly that reason. So a check living there is
   unreachable from the unit suite: requiring it dies at LOAD time with
   `ClassNotFoundException: ui.UiAst$ArcProps`, before any test runs.

   That was measured, not assumed — the suite is rc=0 over 440 tests until a
   test requires fixtures, and dies immediately after. A validator that cannot
   be driven where it lives is the testability smell, and the remedy is to move
   the pure part somewhere requirable rather than to test it through a seam that
   proves less."
  (:require [clojure.string :as str]))

(defn authored-count-problems
  "Every widget whose declared `:authored-count` disagrees with the cards it
   actually holds, as `{:widget :authored-count :actual}` — empty when honest.

   NAMED PER WIDGET BECAUSE THE CORPUS TOTAL STRUCTURALLY CANNOT SEE THIS.
   `:card-count` sums every widget's cards, so two widgets miscounted in
   opposite directions cancel and it passes over a spec that is wrong twice.
   The three this shipped with were exactly that shape — two declaring fewer
   cards than they hold and one declaring more.

   PRESENCE-GUARDED. Every widget block carries the key today, but nothing
   requires it, and demanding it here would report `you omitted an optional key`
   as the same fatal error as `your count is wrong`. Making it mandatory is a
   separate decision with its own reasoning."
  [spec]
  (into []
        (keep (fn [w]
                (let [declared (:authored-count w)
                      actual (count (:cards w))]
                  (when (and declared (not= declared actual))
                    {:widget (:tag w) :authored-count declared :actual actual}))))
        (:widgets spec)))

(defn authored-count-message
  "A one-line human summary of `problems`, for the thrower at the IO edge."
  [problems]
  (str/join ", "
            (map (fn [{:keys [widget authored-count actual]}]
                   (format "%s declares %d but holds %d" widget authored-count actual))
                 problems)))
