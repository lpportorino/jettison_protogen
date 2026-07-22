(ns devcards.golden
  "Raw-framebuffer golden manifest — generate + verify.

   Goldens are sha256 over the RAW RGBA bytes the runner reads out of wasm
   linear memory: encoder-independent, engine-independent (the cross-engine
   determinism gate is what makes one manifest authoritative for both
   wasmtime and GraalWasm hosts). Every entry pins the render protocol it
   was minted under — the TICK BUDGET rides in the manifest so a protocol
   drift fails loudly as a manifest diff, never as a silent re-mint.

   Pure core: `build-manifest` walks cards through a caller-supplied
   render fn (card → {:fb bytes :w int :h int}); `verify` re-renders and
   diffs. IO (EDN read/write) sits at the edge."
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

(defn verify
  "Re-render every manifest card and diff against its golden. Returns
   {:ok [..] :mismatched [{:card :expected :actual}] :missing [..]} —
   :missing = cards the render fn threw for (fixture gone). The caller
   fails on any :mismatched or :missing entry; a card in the CORPUS but
   not the manifest is the coverage gate's finding, not this one's."
  [manifest render!]
  (reduce (fn [acc [card-id {:keys [sha256]}]]
            (let [{:keys [fb error]} (try {:fb (:fb (render! card-id))}
                                          (catch Exception e {:error (ex-message e)}))]
              (cond error (update acc :missing conj {:card card-id :error error})
                    (= sha256 (sha256-hex fb)) (update acc :ok conj card-id)
                    :else (update
                           acc
                           :mismatched
                           conj
                           {:card card-id :expected sha256 :actual (sha256-hex fb)}))))
          {:ok [] :mismatched [] :missing []}
          (:cards manifest)))

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