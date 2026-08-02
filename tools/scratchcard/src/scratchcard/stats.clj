(ns scratchcard.stats
  "Per-render measurements — the half of a scratch run that is not a picture.

  NONE OF THIS EXISTS IN THE SHIPPED LANES. The corpus gate measures one
  aggregate `:elapsed-s` across ~1,400 renders, prints it, and drops it. That
  is the right economy for a gate and useless for an author asking why one
  screen got slower or bigger.

  WHAT IS DELIBERATELY NOT MEASURED, and why the gap is named rather than
  quietly filled: the PER-TICK FLUSH COUNT. `devcards.host/render-card!` is
  the pinned render protocol — the exact call sequence the committed goldens
  and the cross-engine mirror were minted under — and it reports only that at
  least one tick flushed. Counting flushes would mean reimplementing that loop
  here, which is a second copy of a protocol whose whole value is that there
  is one. A drifted copy would produce framebuffers that differ from the
  goldens for a reason unrelated to the screen. So the flush count stays
  unavailable, and `:flush-count-available?` says so in the output rather than
  leaving a reader to assume it was zero.

  THE DIRTY RECT IS NEW CAPABILITY. `controls_get_dirty_rect` and
  `controls_get_dirty_rect_ptr` are exported by the interpreter and used by
  NOTHING in devcards. They answer a question a scratch author actually has —
  which region did this frame actually touch — so this namespace reads them,
  guarded by an export-membership probe rather than call-and-catch.

  TIMINGS ARE MEANINGLESS ALONE ON THIS HOST. Sibling agents, containers and
  other sessions share the box; the same command has been measured swinging
  well over 30% between interleaved runs while load climbed. Every timing is
  therefore recorded beside the load average, so a later reader can tell a
  regression from a busy afternoon."
  (:require
   [devcards.golden :as golden]
   [scratchcard.provenance :as prov])
  (:import
   (org.graalvm.polyglot Value)))

(def gbuf-bytes
  "The scratch buffer `devcards.host/start!` mallocs once per context.

  Mirrors the literal in host.clj's `start!`. Recorded so a screen whose
  serialized form approaches it is visible BEFORE `controls_load_ui` starts
  refusing — the failure at the boundary is a hard error with no gradient."
  262144)

(defn- read-i32-le
  "The little-endian int32 at `offset` in wasm linear memory."
  [^Value mem ^long offset]
  (let [b (fn [i] (bit-and (.readBufferByte mem (+ offset i)) 0xff))]
    (unchecked-int
     (bit-or (b 0)
             (bit-shift-left (b 1) 8)
             (bit-shift-left (b 2) 16)
             (bit-shift-left (b 3) 24)))))

(defn dirty-rect
  "The region the last frame touched, or nil.

  nil covers three distinct cases and the caller should not care which: the
  export is absent from this module, the frame reported clean, or the read
  failed. What matters is that a rect is only ever reported when the
  interpreter actually said so."
  [{:keys [call! mem export?]}]
  (when (and export? (export? "controls_get_dirty_rect")
             (export? "controls_get_dirty_rect_ptr"))
    (when (pos? (.asInt ^Value (call! "controls_get_dirty_rect")))
      (let [ptr (.asLong ^Value (call! "controls_get_dirty_rect_ptr"))
            x1 (read-i32-le mem ptr)
            y1 (read-i32-le mem (+ ptr 4))
            x2 (read-i32-le mem (+ ptr 8))
            y2 (read-i32-le mem (+ ptr 12))]
        {:x1 x1 :y1 y1 :x2 x2 :y2 y2
         ;; Coords are INCLUSIVE here, as everywhere else in this repo's
         ;; geometry — an exclusive reading understates every area by a row
         ;; and a column.
         :w (inc (- x2 x1))
         :h (inc (- y2 y1))}))))

(defn opaque-pixels
  "How many pixels in `rgba` are fully opaque.

  A cheap emptiness signal: a render that is almost entirely transparent is
  usually a screen that failed to lay out, and it is indistinguishable from a
  correct one in a hash."
  [^bytes rgba]
  (let [n (alength rgba)]
    (loop [i 3, c 0]
      (if (>= i n)
        c
        (recur (+ i 4) (if (= -1 (aget rgba i)) (inc c) c))))))

(defn memory-bytes
  "The size of the module's linear memory, or nil when it cannot be read."
  [{:keys [^Value mem]}]
  (try
    (when (.hasBufferElements mem) (.getBufferSize mem))
    (catch Exception _ nil)))

(defn framebuffer-sha
  "sha256 over the RAW framebuffer bytes.

  Uses `devcards.golden/sha256-hex` — the function the committed goldens were
  minted with — so a scratch hash is DIRECTLY comparable to a golden. That
  makes \"did my scratch screen reproduce this corpus card\" an answerable
  question, which it would not be under a second hashing path."
  ^String [^bytes rgba]
  (golden/sha256-hex rgba))

(defn collect
  "Per-render statistics.

  `timings` is a map of phase -> milliseconds measured by the caller, which
  owns the clock because only it knows where one phase ends and the next
  begins."
  [{:keys [host ^bytes framebuffer pb-bytes dump-bytes timings w h]}]
  (let [expected (* (long w) (long h) 4)]
    (cond-> {:timings-ms timings
             :load-average (prov/load-average)
             :framebuffer {:bytes (alength framebuffer)
                           :expected-bytes expected
                           ;; A short framebuffer is a torn read, not a small
                           ;; screen. Stated so a reader never divides by a
                           ;; length they assumed.
                           :complete? (= expected (alength framebuffer))
                           :sha256 (framebuffer-sha framebuffer)
                           :opaque-pixels (opaque-pixels framebuffer)}
             :input {:pb-bytes pb-bytes
                     :gbuf-bytes gbuf-bytes
                     :gbuf-used-ratio (double (/ (long pb-bytes) gbuf-bytes))}
             :dump {:bytes dump-bytes}
             :wasm {:memory-bytes (memory-bytes host)}
             ;; Named, not omitted — see the namespace docstring.
             :flush-count-available? false}
      (dirty-rect host) (assoc :dirty-rect (dirty-rect host)))))
