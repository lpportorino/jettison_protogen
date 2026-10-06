(ns devcards.corpus
  "Generic themed-corpus DRIVER — the render loop, error partitioning, and
   golden-set diffing every bring-your-own-corpus consumer plugs into,
   promoted from the private screen-corpus driver so a consumer authors
   corpus CONFIG (its screens, variants, and render fn), not corpus
   infrastructure. Pairs with `devcards.golden` (manifest IO + hashing),
   `devcards.probe`/`devcards.pointer` (the E2E half), and `devcards.docgen`
   (the doc half).

   A FAILED RENDER IS RECORDED, NOT ABORTED, AND IT KEEPS ITS EVIDENCE. Each
   `:errors` entry carries `:error` — the message, exactly as it always has —
   and `:trace`, the closed record `devcards.trace/throwable-trace` builds:
   class, bounded message, leading frames with a counted remainder, and a
   bounded cause chain that says whether it was cut. When recording that trace
   itself throws, the entry is DEGRADED instead: `:error` plus
   `:trace-unavailable` naming what threw, and no `:trace`.
   `error-entry-problems` states both shapes executably. The driver is the only
   witness to a render that fails once under load and passes on the re-run, so
   a message alone would be all that survives it.

   The driver knows nothing about hosts, packages, or themes-as-such: a
   VARIANT is any map with a :key (dark/light, families, breakpoints — the
   consumer decides), a SCREEN any map with an :id, and the render fn owns
   everything in between. HOW-public / WHAT-private: no consumer content
   ever lands here."
  (:require [clojure.set :as set]
            [devcards.trace :as trace]))

(set! *warn-on-reflection* true)

;; ── the card KEY SET ─────────────────────────────────────────────────────
;;
;; A render fn's result map is the CONSUMER's data, so the driver carries what
;; it does not itself need. Three classes, and the split is the contract:
;; SKELETON keys the driver has always emitted, CONSUMED keys it reads and
;; deliberately drops, and DRIVER-OWNED keys it refuses outright.

(def ^:private card-skeleton
  "The card keys a golden entry ALWAYS carries, defaulted to nil. The render
   result merges OVER this, which fixes both the shape and the printed key
   order: a render fn returning exactly the documented set produces the same
   bytes it would under a fixed-key build, and one that omits :w or :h gets an
   explicit nil rather than a missing key. A consumer's committed manifest is
   EDN it diffs, so a key that silently stopped appearing would read as drift
   in every entry at once."
  {:sha256 nil :w nil :h nil})

(def ^:private consumed-card-keys
  "Keys the driver READS out of a render result and does not carry into the
   card, each routed to a channel of its own. Letting either ride along would
   push per-card bulk into every golden entry: `:findings` a finding vector,
   `:tree` a whole dump tree.

   `:tree` IS ROUTED RATHER THAN FORBIDDEN because a consumer that renders a
   corpus already HAS each tree in hand — it judged the card with it — and a
   second pass that re-renders purely to recover it does the same work twice
   and describes a DIFFERENT render than the images beside it. The channel
   hands the tree back without it reaching `card-of`.

   NOTE THIS SET IS A BLACKLIST, so adding to it CHANGES BEHAVIOUR for any
   consumer whose render fn already returns that key: it stops riding into the
   golden entry and starts arriving on the channel. Measured before `:tree` was
   added — no committed manifest in this repo or in the pin-bumping consumer
   carried it (0 of 8 here), so nothing moved; a private corpus that did carry
   one sees a golden diff at its next mint, never silent corruption."
  [:findings :tree])

(def ^:private driver-owned-card-keys
  "Keys the DRIVER owns on a result. A render fn returning one is REFUSED
   rather than silently honoured or silently ignored — both of those are the
   quiet failure this seam exists to stop. What honouring one costs, measured
   on this driver: `:id` or `:variant` overwrites the tag, and the tag is the
   golden map's KEY, so a four-card corpus collapses into one entry per
   variant; `:error` is the success/failure discriminator, so every SUCCESSFUL
   render partitions into `:errors` and the golden maps come back EMPTY.
   Neither prints anything."
  [:variant :id :error])

(def ^:private error-entry-keys
  "Every key an `:errors` entry may carry. An entry holds `:variant`, `:id` and
   `:error`, plus EXACTLY ONE of `:trace` and `:trace-unavailable`.

   `:error` is the failure's MESSAGE (its class, prefixed `class `, when it has
   none) — the meaning it has always had, so a consumer reading it as a string
   is unaffected. `:trace` is the diagnosis beside it, a closed map whose shape
   `devcards.trace/trace-problems` states. `:trace-unavailable` replaces it only
   when recording the trace itself threw, and names the class of what threw.
   `error-entry-problems` is the executable form of both shapes.

   :trace IS A KEY ON THE ENTRY RATHER THAN A CHANNEL OF ITS OWN, unlike
   `:trees`: a trace describes ONE failure and is read together with it, so
   splitting them would make every reader re-join two vectors by tag to
   recover a fact that was never separate."
  [:variant :id :error :trace :trace-unavailable])

(defn- failure-message
  "`:error` for `t`: its message, or its class when it has none — or when
   asking for the message throws, since a throwable may override `getMessage`
   and the record of the failure must not be lost to it."
  ^String [^Throwable t]
  (or (try (ex-message t) (catch Throwable _ nil))
      (str (class t))))

(defn- failure-entry
  "The tagged `:errors` record for a render that threw `t`.

   GUARDED, because it runs inside the very catch that keeps one failing screen
   from aborting the sweep: recording the trace calls accessors `t` may
   override, and a throw from one of them would escape that catch and discard
   every other screen's result — the failure the catch exists to prevent. So a
   trace that cannot be recorded DEGRADES the entry to `:error` alone, marked
   with `:trace-unavailable` naming the class of what threw. It does not drop
   the screen and does not stop the sweep."
  [tag ^Throwable t]
  (let [traced (try (trace/throwable-trace t)
                    (catch Throwable capture-failure capture-failure))]
    (if (map? traced)
      (assoc tag :error (failure-message t) :trace traced)
      (assoc tag
             :error (failure-message t)
             :trace-unavailable (.getName (class traced))))))

(defn error-entry-problems
  "Every way `entry` departs from the shape of one `:errors` entry, as strings;
   empty when it conforms. This is that shape's one executable statement, and
   the failure-path tests hold every entry they make the driver produce to it.

   TWO shapes, and an entry is exactly one of them: TRACED —
   {:variant :id :error :trace}, `:trace` judged by
   `devcards.trace/trace-problems` — or DEGRADED — {:variant :id :error
   :trace-unavailable}, the latter naming the class of what threw while the
   trace was being recorded. `:error` is a string in both — possibly EMPTY: a
   throwable with an empty message keeps it, exactly as before; `:variant`
   and `:id` are the tag and are not judged here."
  [entry]
  (let [ks (when (map? entry) (set (keys entry)))
        traced #{:variant :id :error :trace}
        degraded #{:variant :id :error :trace-unavailable}]
    (cond
      (not (map? entry)) [(str "entry is not a map: " (pr-str entry))]
      (not (or (= traced ks) (= degraded ks)))
      [(str "entry keys " (vec (sort-by pr-str ks)) " are neither "
            (vec (sort traced)) " nor " (vec (sort degraded)))]
      :else
      (cond-> []
        (not (string? (:error entry)))
        (conj "entry :error is not a string")
        (contains? entry :trace)
        (into (map #(str "entry :trace: " %) (trace/trace-problems (:trace entry))))
        (and (contains? entry :trace-unavailable)
             (not (and (string? (:trace-unavailable entry)) (seq (:trace-unavailable entry)))))
        (conj "entry :trace-unavailable is not a non-blank string")))))

(defn- card-of
  "The golden-map entry for one successful render: the render fn's own map,
   minus the keys the driver consumes, merged over the skeleton. EXTRA KEYS
   RIDE ALONG — that is the point of the seam. A consumer computes per-card
   data at render time (a geometry rect, a crop, a probe count) and a driver
   that rebuilt the entry from a fixed key set would discard it here, silently,
   where nothing downstream can tell an absent key from an unset one."
  [result]
  (merge card-skeleton (apply dissoc result consumed-card-keys)))

(defn- driver-owned-clashes
  "Every (variant, id) whose render result carried a driver-owned key, with the
   keys it carried. Computed over the WHOLE realized sweep rather than thrown
   from inside the parallel map, so the refusal names all of them — and names
   the same ones on every run, instead of whichever task lost the race."
  [results]
  (let [owned (set driver-owned-card-keys)]
    (into []
          (keep (fn [{:keys [variant id card]}]
                  (when-let [ks (seq (filter owned (keys card)))]
                    {:variant variant :id id :keys (vec (sort ks))})))
          results)))

(defn render-corpus
  "Render every (screen, variant) pair once, in parallel. `screens` = maps
   each carrying :id; `variants` = maps each carrying :key; `result1` =
   (fn [screen variant] -> {:sha256 .. :w .. :h .. :findings [..]}) — a
   throw becomes a tagged {:variant :id :error msg :trace {..}} entry instead
   of aborting the sweep (a corpus reports every failing screen). Returns
   {:by-variant {vkey (sorted-map id {:sha256 :w :h ..})}
    :findings [finding + :variant + :id ...]
    :trees [{:variant :id :tree} ...]
    :errors [{:variant :id :error :trace} ...]}   ; or :trace-unavailable
   — errored pairs are partitioned OUT of the golden maps (only successful
   renders get a golden). Empty screens or variants THROW: a zero-pair
   corpus proves nothing and must never return a vacuous green.

   THE CARD IS THE RENDER FN'S OWN MAP, so a key BEYOND :sha256/:w/:h rides
   into the golden entry unchanged — a consumer recording per-card data it
   computes at render time (a geometry rect, a crop, a probe count) threads it
   through here rather than forking the driver. :findings and :tree are the
   keys read and dropped, each to a channel of its own (`consumed-card-keys`);
   :sha256/:w/:h are always present, nil when the render fn omits them.
   Returning :variant, :id or :error THROWS, naming every offending pair:
   those are the driver's own, and honouring one silently corrupts the golden
   map's keys or its success partition.

   WHAT A FAILURE CARRIES. `:error` is the thrown message — unchanged in
   meaning, so a consumer that prints or matches it keeps working. `:trace` is
   the evidence beside it (`devcards.trace/throwable-trace`): the class of every
   throwable on the cause chain, each one's message, its LEADING frames with the
   remainder counted, and — for a polyglot exception — whether the engine, the
   host or the guest raised it. Its bounds are fixed in `devcards.trace`, so the
   trace does not grow with the depth or the message of whatever threw; `:error`
   alone keeps the whole message, exactly as it always has. A trace that cannot
   be recorded — an accessor of the throwable itself throws — leaves
   `:trace-unavailable` in its place (`failure-entry`), never a lost screen.

   THE CATCH IS `Throwable`, AND THAT IS DELIBERATE. An `Error` thrown by a
   render fn — an `assert` failing is an `AssertionError` — is one screen's
   failure, and catching only `Exception` would let it escape the parallel map
   and abort the sweep, discarding every other screen's result to report the
   first. The record no longer hides which kind it was: the trace names the
   class, so an `Error` is never mistaken for a rejected screen."
  [screens variants result1]
  (when (empty? screens)
    (throw (ex-info "refusing to drive an EMPTY screen set" {})))
  (when (empty? variants)
    (throw (ex-info "refusing to drive an EMPTY variant set" {})))
  (let [results (doall
                 (pmap (fn [[screen variant]]
                         (let [tag {:variant (:key variant) :id (:id screen)}]
                           (try (assoc tag :card (result1 screen variant))
                                (catch Throwable t
                                  (failure-entry tag t)))))
                       (for [variant variants screen screens] [screen variant])))
        clashes (driver-owned-clashes results)]
    (when (seq clashes)
      (throw (ex-info "render fn returned driver-owned card key(s)"
                      {:driver-owned driver-owned-card-keys :clashes clashes})))
    (let [{ok true errored false} (group-by #(nil? (:error %)) results)
          by-variant (into {}
                           (map (fn [[vk rs]]
                                  [vk (into (sorted-map)
                                            (map (fn [{:keys [id card]}] [id (card-of card)]))
                                            rs)]))
                           (group-by :variant (or ok [])))
          findings (into []
                         (mapcat (fn [{:keys [variant id card]}]
                                   (map #(assoc % :variant variant :id id) (:findings card))))
                         (or ok []))
          ;; `keep`, not `map`: a render fn that returns no :tree contributes
          ;; NOTHING rather than a {:tree nil} entry, so a consumer can tell
          ;; "this driver never carried trees" from "this card had none" —
          ;; the same distinction :findings gets for free by being a seq.
          trees (into []
                      (keep (fn [{:keys [variant id card]}]
                              (when-let [t (:tree card)]
                                {:variant variant :id id :tree t})))
                      (or ok []))
          errors (mapv #(select-keys % error-entry-keys) (or errored []))]
      {:by-variant by-variant :findings findings :trees trees :errors errors})))

(defn diff-cards
  "Diff a committed golden card map against a freshly rendered one (both
   id -> {:sha256 ..}). Returns {:mismatched [{:id :expected :actual}]
   :missing [ids committed but not rendered] :unexpected [ids rendered but
   not committed]} — the three drift classes a verify lane fails on."
  [expected actual]
  (let [expected-ids (set (keys expected))
        actual-ids (set (keys actual))]
    {:mismatched (vec (for [id (sort (set/intersection expected-ids actual-ids))
                            :let [e (get-in expected [id :sha256])
                                  a (get-in actual [id :sha256])]
                            :when (not= e a)]
                        {:id id :expected e :actual a}))
     :missing (vec (sort (set/difference expected-ids actual-ids)))
     :unexpected (vec (sort (set/difference actual-ids expected-ids)))}))
