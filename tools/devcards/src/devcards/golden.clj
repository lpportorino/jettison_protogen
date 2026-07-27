(ns devcards.golden
  "Raw-framebuffer golden manifest — generate + verify.

   Goldens are sha256 over the RAW RGBA bytes the runner reads out of wasm
   linear memory: encoder-independent, engine-independent (the cross-engine
   determinism gate is what makes one manifest authoritative for both
   wasmtime and GraalWasm hosts). Every entry pins the render protocol it
   was minted under — the TICK BUDGET rides in the manifest so a protocol
   drift fails loudly as a manifest diff, never as a silent re-mint.

   Pure core: `build-manifest` walks cards through a caller-supplied
   render fn (card → {:fb bytes :w int :h int}). IO (EDN read/write) sits
   at the edge.

   DIFFING IS NOT HERE, AND THAT IS `devcards.corpus`'s CLAIM, NOT A GAP:
   its own docstring calls itself \"the render loop, error partitioning, and
   golden-set diffing every bring-your-own-corpus consumer plugs into\", and
   names this ns as the \"manifest IO + hashing\" half. `corpus/diff-cards`
   is that diff, it is unit-tested, and it is what the devcards gate calls
   (`gates/golden-drift-findings` ← `core/-main`).

   A `verify` fn used to live here that re-rendered every manifest card
   through a caller-supplied render fn and diffed. It is REMOVED rather than
   wired, and the two reasons are worth keeping because they are the ones a
   consumer needs: it had ZERO call sites anywhere while its docstring
   described a caller (\"The caller fails on any :mismatched or :missing
   entry\") that did not exist; and against `corpus/diff-cards` it was
   strictly worse at the one job — it cost a second full render of the corpus
   (measured: 5.4s to re-render 234 dark cards whose hashes the mint had
   already computed) and it saw only two drift classes, explicitly
   disclaiming the third (a card in the corpus but absent from the manifest).
   CONSUMER MIGRATION: pass the card maps you already hold — the ones
   `corpus/render-corpus` returns in `:by-variant` — to `corpus/diff-cards`
   instead. You gain `:unexpected` and pay no renders."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp])
  (:import [java.security MessageDigest]))

(set! *warn-on-reflection* true)

(defn sha256-hex
  "Lowercase hex sha256 of a byte array."
  ^String [^bytes b]
  (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") b))))

(defn build-manifest
  "Render every card and mint its golden entry. `cards` = seq of card-id
   strings; `render!` = (fn [card-id] {:fb bytes :w int :h int});
   `protocol` = the render-protocol facts the mint ran under (tick count,
   tick ms, dpi — from the conventions manifest, restated here so the
   manifest is self-describing). Returns the manifest map."
  [cards render! protocol]
  (when (empty? cards) (throw (ex-info "refusing to mint an EMPTY golden manifest" {})))
  {:protocol protocol
   :cards (into (sorted-map)
                (map (fn [card-id]
                       (let [{:keys [fb w h]} (render! card-id)]
                         [card-id {:sha256 (sha256-hex fb) :w w :h h}])))
                cards)})

(defn write-manifest!
  "Persist a manifest as pretty EDN (stable ordering: :cards is a sorted
   map, so re-mints diff minimally)."
  [manifest ^String path]
  (io/make-parents path)
  (with-open [w (io/writer path)] (pp/pprint manifest w))
  nil)

(defn read-manifest
  "Read a manifest EDN; throws on a shape that lacks :cards or :protocol
   (a truncated/hand-damaged manifest must not verify vacuously)."
  [^String path]
  (let [m (edn/read-string (slurp path))]
    (when-not (and (map? m) (map? (:cards m)) (map? (:protocol m)))
      (throw (ex-info "malformed golden manifest" {:path path})))
    (when (empty? (:cards m))
      (throw
       (ex-info
        "EMPTY golden manifest — nothing to verify is a
                       failure, not a pass"
        {:path path})))
    m))