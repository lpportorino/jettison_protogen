(ns devcards.host
  "GraalWasm host for the pinned canonical controls.wasm — the devcard runner's
   engine room. PUBLIC tooling (final home: protogen tools/devcards/): boots the
   vendored renderer binary, drives one devcard render, hands back the RAW RGBA
   framebuffer bytes (the golden gate hashes THESE — encoder-independent) and
   the dump_tree DOM JSON.

   PROVENANCE: the engine/source-cache/env-import/WasiMapDirs mechanics are
   ADAPTED from the in-fleet production GraalWasm host that first proved them
   (a private sibling repo) — not independently re-derived. How that
   provenance is recorded in the PUBLIC home (a named .ported-from.edn pin
   vs an anonymous in-fleet note) is an operator decision at the landing
   review; this docstring is the honest interim record.

   Mechanism notes (proven in-fleet):
   - ONE shared Engine + content-keyed Source cache: Truffle caches compiled
     code per Engine, so instantiation drops to ~1-4ms warm; the Source key is
     [path sha256] so an in-place re-vendor can never serve a stale module.
   - Fresh Context PER CARD: hermetic — no widget/subject state bleeds between
     cards; the shared Engine keeps that cheap.
   - The four `env` imports (host_command/host_report/host_event/
     host_proxy_report) are instantiation-MANDATORY for controls.wasm; the
     runner captures them (a devcard emitting a device command is a fixture
     bug the invariant layer flags).
   - dump_tree returns a pointer into linear memory — copied out before any
     subsequent call.
   - allowIO(IOAccess/ALL) is mandatory for WasiMapDirs (the assets preopen:
     fonts + icons the renderer reads at render time)."
  (:require [clojure.java.io :as io])
  (:import [java.io File]
           [java.security MessageDigest]
           [org.graalvm.polyglot Context Engine Source Value]
           [org.graalvm.polyglot.io IOAccess]
           [org.graalvm.polyglot.proxy ProxyExecutable ProxyObject]))

(set! *warn-on-reflection* true)

(defn- assert-optimizing-runtime!
  "Refuse to run without a Truffle optimizing runtime, returning the engine.

   Polyglot DEGRADES SILENTLY: on a JDK without JVMCI + the Graal compiler it
   builds a working engine that interprets every wasm instruction, prints a
   WARNING nobody reads in a 25-minute log, and produces identical results —
   only far slower. Measured on this corpus: ~290ms per render interpreted,
   against roughly a twentieth of that with the optimizing runtime — the
   single largest leg of the renderer battery, spent on a misconfiguration
   rather than on work.

   A warning is the wrong shape for that. It is a REQUIRED toolchain component
   that is absent, so it hard-fails here rather than being absorbed — the same
   posture the batteries take toward a missing gate tool. Note the failure is
   also self-concealing in the worst way: the run still PASSES, so nothing
   downstream ever reports it, and the only symptom is time.

   The engine reports `Interpreted` in that state and the GraalVM runtime's own
   name (e.g. `GraalVM CE`) otherwise — both verified on this toolchain, which
   is what makes this check non-vacuous."
  ^Engine [^Engine engine]
  (let [impl (.getImplementationName engine)]
    (when (= "Interpreted" impl)
      (throw (ex-info
              (str "GraalWasm has NO optimizing runtime — refusing to run.\n"
                   "  engine implementation: " impl "\n"
                   "  cause: this JDK lacks JVMCI + the Graal compiler.\n"
                   "  fix:   run inside protogen's own toolchain image, which\n"
                   "         installs GraalVM CE for exactly this reason:\n"
                   "           ./tools/uber.sh 'make -f renderer.mk check-renderer'\n"
                   "         A stock JDK (Temurin/OpenJDK) cannot run this lane;\n"
                   "         see .claude/rules/uber-container.md.")
              {:engine-implementation impl
               :engine-version (.getVersion engine)
               :java-vm (System/getProperty "java.vm.name")
               :java-home (System/getProperty "java.home")})))
    engine))

(defonce ^:private shared-engine
  (delay (assert-optimizing-runtime!
          (.build (Engine/newBuilder (into-array String ["wasm"]))))))

(defn- sha256-file
  ^String [^String path]
  (let [d (MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream path)]
      (let [buf (byte-array 65536)]
        (loop [] (let [n (.read in buf)] (when (pos? n) (.update d buf 0 n) (recur))))))
    (format "%064x" (BigInteger. 1 (.digest d)))))

(defonce ^:private source-cache (atom {}))

(defn- wasm-source
  "Compiled-module Source, cached per [path content-sha] — identity by
   CONTENT so an in-place wasm re-pin invalidates; stale same-path entries
   are dropped so the cache stays bounded."
  ^Source [^String wasm-path]
  (let [k [wasm-path (sha256-file wasm-path)]]
    (or (get @source-cache k)
        (let [src (-> (Source/newBuilder "wasm" (File. wasm-path))
                      (.name "controls")
                      (.build))]
          (swap! source-cache #(-> (into {} (remove (fn [[[p _] _]] (= p wasm-path)) %))
                                   (assoc k src)))
          src))))

(defn- env-imports
  "The renderer's mandatory `env` import object. Everything is captured into
   `captured` (a devcard is expected to emit NOTHING on the command/report
   lanes — the invariant layer asserts that)."
  [mem-box captured]
  (let [read-bytes (fn [^long ptr ^long len]
                     (let [^Value mem @mem-box
                           arr (byte-array len)]
                       (.readBuffer mem ptr arr 0 (int len))
                       arr))
        capture! (fn [k]
                   (reify
                     ProxyExecutable
                     (execute [_ args]
                       (swap! captured update
                              k
                              conj
                              (read-bytes (.asLong ^Value (aget args 0))
                                          (.asLong ^Value (aget args 1))))
                       (int 0))))]
    (ProxyObject/fromMap
     {"host_command" (capture! :commands)
      "host_report" (capture! :reports)
      "host_event" (capture! :events)
      "host_proxy_report"
      (reify
        ProxyExecutable
        (execute [_ args]
          (let [id (String. ^bytes
                    (read-bytes (.asLong ^Value (aget args 0))
                                (.asLong ^Value (aget args 1))))]
            (swap! captured update
                   :proxy-reports
                   conj
                   (into [id] (mapv #(.asInt ^Value (aget args %)) (range 2 10)))))
          (int 0)))})))

(def ^:private supported-abis
  "controls.wasm ABI versions this runner speaks. Bump DELIBERATELY with the
   wasm pin (the ABI gate below fails loud on mismatch — never render against
   an unknown contract)."
  #{2 3 4})

(defn start!
  "Boot a FRESH context over the shared engine and init the renderer.
   Returns {:ctx :mem :call! :push! :captured :fb-ptr :abi :gbuf :w :h}.
   Throws on ABI/fb-format mismatch (fail loud, never render a lie)."
  [{:keys [wasm assets w h]}]
  (let [captured (atom {:commands [] :reports [] :events [] :proxy-reports []})
        ctx (-> (Context/newBuilder (into-array String ["wasm"]))
                (.engine ^Engine @shared-engine)
                (.allowExperimentalOptions true)
                (.allowIO IOAccess/ALL)
                (.option "wasm.Builtins" "wasi_snapshot_preview1")
                (.option "wasm.WasiMapDirs"
                         (str "/::" (.getCanonicalPath (File. ^String assets))))
                (.build))
        modv ^Value (.eval ctx ^Source (wasm-source wasm))
        mem-box (atom nil)
        instv ^Value
        (.newInstance modv
                      (object-array [(ProxyObject/fromMap
                                      {"env" (env-imports mem-box captured)})]))
        inst ^Value (if (.hasMember instv "exports") (.getMember instv "exports") instv)
        export (fn ^Value [^String n] (.getMember inst n))
        ;; Membership, not invocation. A wasm built before an export existed is
        ;; a REAL state here (a consumer pins its own build), and asking for a
        ;; missing member throws — which a caller cannot tell apart from the
        ;; export existing and failing. Probing first is what lets an absent
        ;; capability be REPORTED as absent instead of surfacing as a crash.
        export? (fn [^String n] (.hasMember inst n))
        mem ^Value (export "memory")
        _ (reset! mem-box mem)
        call! (fn ^Value [^String n & xs]
                (.execute ^Value (export n) (object-array (map int xs))))]
    (.execute ^Value (export "_initialize") (object-array 0))
    (call! "controls_init" w h)
    (let [abi (.asInt ^Value (call! "controls_abi_version"))
          fbf (.asInt ^Value (call! "controls_fb_format"))]
      (when-not (and (contains? supported-abis abi) (= 1 fbf))
        (throw (ex-info "controls.wasm ABI gate failed"
                        {:abi abi :fb-format fbf :supported supported-abis})))
      (let [gbuf (.asLong ^Value (call! "malloc" 262144))
            push! (fn ^long [^String export-name ^bytes pb]
                    (dotimes [i (alength pb)] (.writeBufferByte mem (+ gbuf i) (aget pb i)))
                    (.asInt ^Value (call! export-name gbuf (alength pb))))]
        {:ctx ctx
         :mem mem
         :call! call!
         :export? export?
         :push! push!
         :captured captured
         :fb-ptr (.asLong ^Value (call! "controls_get_framebuffer"))
         :abi (long abi)
         :gbuf gbuf
         :w (long w)
         :h (long h)}))))

(defn close!
  "Tear down the card's context (controls_destroy first — idempotent per the
   ABI contract — then the polyglot context)."
  [{:keys [call! ^Context ctx]}]
  (call! "controls_destroy")
  (.close ctx))

(defn tick!
  "Advance the renderer clock by `ms`; returns the dirty flag."
  ^long [{:keys [call!]} ^long ms]
  (.asInt ^Value (call! "controls_tick" ms)))

(defn set-breakpoint!
  ^long [{:keys [call!]} ^long bp]
  (.asInt ^Value (call! "controls_set_breakpoint" bp)))

(defn set-theme-dark!
  ^long [{:keys [call!]} ^long dark]
  (.asInt ^Value (call! "controls_set_theme_dark" dark)))

(defn set-dpi!
  ^long [{:keys [call!]} ^long dpi]
  (.asInt ^Value (call! "controls_set_dpi" dpi)))

(defn set-theme-family!
  "controls_set_theme_family: the family ids are `asgard_theme_family_t` in
   renderer/src/theme.h (asgard is the default; the vanilla-vs-stock hash
   equality is the idempotency gate — see gates/vanilla-stock-findings). ABI 3+
   only — the runner checks :abi before calling."
  ^long [{:keys [call! abi]} ^long family]
  (when (< abi 3) (throw (ex-info "theme-family requires ABI >= 3" {:abi abi})))
  (.asInt ^Value (call! "controls_set_theme_family" family)))

(defn load-ui!
  "Push a fixture .pb through controls_load_ui; rc!=0 throws (a fixture the
   renderer rejects is a broken fixture, never a skip)."
  [{:keys [push!] :as _host} ^bytes screen-pb]
  (let [rc (push! "controls_load_ui" screen-pb)]
    (when-not (zero? rc) (throw (ex-info "controls_load_ui rejected fixture" {:rc rc})))
    rc))

(defn read-framebuffer!
  "Copy the RAW RGBA framebuffer out of linear memory. THESE bytes are the
   golden-hash input (deterministic: pinned wasm + fixture + ticks); PNG/JPEG
   encoding happens downstream and never participates in the gate."
  ^bytes [{:keys [^Value mem fb-ptr w h]}]
  (let [n (* (long w) (long h) 4)
        out (byte-array n)]
    (.readBuffer mem fb-ptr out 0 (int n))
    out))

;; ── The pinned render protocol ──────────────────────────────────────────
;; Mirrors the Rust harness EXACTLY (wasm_harness/src/main.rs render_all +
;; tests/visual_regression.rs render_fixture_at_dpi, verified against
;; wasm_harness/src/lib.rs): set_breakpoint → set_theme_dark → set_dpi →
;; load_ui → tick × RENDER-TICKS at TICK-MS each (pinned budget, NOT an
;; adaptive settle — a render must be a deterministic function of module +
;; inputs; the demo's anim-frozen values are functions of ticks × ms) →
;; at least one tick must report a flush → read framebuffer.
;; MIRRORED: corpus/spec.edn's `:render` section restates these same values
;; (:render-ticks/:tick-ms/:dpi); dev/t22_smoke.clj asserts this ns's
;; constants agree with the spec's copy, so a drift between the Rust harness
;; and the corpus fails loudly there.
(def render-ticks "Pinned tick count per render (mirror of lvgl_harness::RENDER_TICKS)." 3)

(def tick-ms "Elapsed ms per tick (mirror of lvgl_harness::TICK_MS)." 16)

(def default-dpi "The harness's default DPI (visual_regression.rs `DPI`)." 160)

(defn render-card!
  "Render ONE devcard under the pinned protocol. `card` keys:
   :pb (bytes, required) :bp :dark :dpi (defaults 0/1/160).
   Returns the raw RGBA framebuffer bytes. Throws if no tick flushed —
   a render that never flushed is a blank lie, never a golden."
  ^bytes [host {:keys [^bytes pb bp dark dpi] :or {bp 0 dark 1 dpi default-dpi}}]
  (set-breakpoint! host bp)
  (set-theme-dark! host dark)
  (set-dpi! host dpi)
  (load-ui! host pb)
  (let [flushed
        (loop [i 0
               f false]
          (if (= i render-ticks) f (recur (inc i) (or (pos? (tick! host tick-ms)) f))))]
    (when-not flushed
      (throw (ex-info "no flush within the pinned tick budget"
                      {:ticks render-ticks :tick-ms tick-ms})))
    (read-framebuffer! host)))

(defn utf8-string
  "Decode `buf` — the renderer's dump payload — as UTF-8.

   SPLIT OUT OF `read-cstring` SO IT CAN BE TESTED AT ALL. That function reads
   through a GraalVM `Value`, which is a final class and cannot be faked, so the
   decode was reachable only by standing up a wasm module. The bug below was
   therefore invisible to every unit test in this suite while being visible in a
   consumer's generated pages.

   THE BUG IT FIXES: the byte loop used to append one CHAR PER BYTE, which is
   Latin-1, and so double-encoded every multi-byte glyph the renderer emits.
   ASCII is a fixed point of that mistake, which is why it survived — every
   label this corpus asserts on is ASCII. The RENDERS were always correct: the
   renderer never sees this String, only a reader of the text does."
  ^String [^bytes buf]
  (String. buf java.nio.charset.StandardCharsets/UTF_8))

(defn- read-cstring
  "The NUL-terminated dump buffer at `ptr`, copied out of linear memory into a
   String.

   ONE implementation, shared by every dump export, because the renderer gives
   them all the SAME contract: the returned pointer is into the one shared dump
   buffer, so the bytes must be copied out before the next call into the module.
   A second hand-rolled copy of this loop beside the first would be a silently
   divergent source for a rule nobody re-checks.

   THE BYTES ARE UTF-8 AND ARE DECODED AS SUCH — an earlier form appended one
   CHAR PER BYTE, which is Latin-1, and that silently double-encoded every
   multi-byte glyph the renderer emits. Measured on a consumer's generated
   pages: a degree sign (U+00B0, UTF-8 `c2 b0`) became the two chars U+00C2
   U+00B0 and was written back out as `c3 82 c2 b0`; the icon font's
   warning triangle (U+F071, UTF-8 `ef 81 b1`) became `c3 af c2 81 c2 b1`.
   ASCII is a fixed point of that bug, which is why it survived — every label
   the corpus asserts on is ASCII, and the RENDERS were always correct because
   the renderer never sees this String. Only a reader of the text did."
  ^String [^Value mem ^long ptr]
  (let [out (java.io.ByteArrayOutputStream.)]
    (loop [p ptr]
      (let [b (.readBufferByte mem p)]
        (if (zero? b)
          (utf8-string (.toByteArray out))
          (do (.write out (bit-and b 0xFF)) (recur (inc p))))))))

(def draw-palette-export
  "The renderer export carrying the draw-stream palette observation."
  "controls_dump_draw_palette")

(defn dump-draw-palette!
  "The draw-stream palette observation as one JSON String, or NIL when this
   wasm has no such export.

   NIL MEANS ONE THING ONLY: the module predates the observer. That is a real
   state — a consumer pins its own build — and it is why this probes membership
   rather than calling and catching, which could not tell a missing export from
   an export that threw. The caller's duty is to pass NO :draw-palette in that
   case, so `devcards.palette` reaches its :observer-not-exposed reason and says
   out loud that it could not look. Substituting an empty observation here would
   erase that distinction and report a clean screen instead.

   NO TRUNCATION MEMBRANE, and the asymmetry with `dump-tree!` is deliberate on
   the RENDERER's side rather than overlooked here. `controls_dump_tree` signals
   overflow by appending a sentinel to already-cut JSON, so the bytes are not
   parseable and `normalize-dump` has to intervene before any parser sees them.
   This export instead emits a COMPLETE object carrying `\"overflow\":true`, so
   an overflowed observation parses fine and its own consumer decides what to do
   with it. Do not copy the membrane across; there is nothing here for it to fix.

   Throws if the host map carries no :export? — that is a wiring defect in the
   caller, never an answer about the module, and returning NIL for it would
   quietly report every card as unobserved."
  ^String [{:keys [^Value mem call! export?]}]
  (when-not export?
    (throw (ex-info "host map has no :export? probe; cannot tell a missing export from a broken one"
                    {:export draw-palette-export})))
  (when (export? draw-palette-export)
    (read-cstring mem (.asLong ^Value (call! draw-palette-export)))))

(defn dump-tree-raw!
  "Diagnostic-only controls_dump_tree bytes, copied out of linear memory
   before any next call. A truncated result is deliberately NOT valid JSON;
   ordinary callers need dump-tree!, which normalises the renderer sentinel
   before a parser can see it."
  ^String [{:keys [^Value mem call!]}]
  (read-cstring mem (.asLong ^Value (call! "controls_dump_tree"))))

(defn normalize-dump
  "The truncation membrane, as a pure String -> String so it can be gated.

   A renderer-buffer overflow ends with `,\"truncated\":true` but is otherwise
   structurally cut JSON. Detect that suffix BEFORE any caller can parse it and
   return a canonical root carrying the same positive key. The return contract
   stays one JSON String — never a String plus an out-of-band flag a copied
   host can forget — while every existing parse site reaches the
   :dump-truncated invariant instead of throwing first.

   SEPARATE FROM `dump-tree!` BECAUSE OF WHAT CAN GATE IT — and the thing that
   needs gating is the TRUNCATION path, not `dump-tree!`. `dump-tree!` itself
   runs end-to-end on every corpus card under `fixtures-prebuilt` and
   `gallery-prebuilt`, which the devcards workflow does run. But NO CORPUS CARD
   OVERRUNS THE BUFFER, so none of those runs ever takes this branch: its only
   end-to-end exerciser is the `dump-contracts` probe, which lives in the
   renderer workflow, whose `paths:` filter does not name `tools/devcards/**`.
   A push deleting this clause and touching nothing else would start no
   workflow that runs the probe. As a pure fn the clause is reachable from the
   unit suite, which the devcards workflow DOES run on such a push.

   WHAT THAT LEAVES UNGATED, stated rather than implied: the unit suite pins
   this fn's BEHAVIOUR, not its WIRING. Rewriting `dump-tree!` below to call
   `dump-tree-raw!` directly leaves the whole suite green, and `dump-contracts`
   — reachable locally through `check-renderer`, and in CI only from a
   renderer-side push — remains the sole gate on the composition."
  ^String [^String raw]
  (if (.endsWith raw ",\"truncated\":true")
    "{\"truncated\":true,\"children\":[]}"
    raw))

(defn dump-tree!
  "controls_dump_tree -> one parseable DOM JSON String, normalised by
   `normalize-dump` so a truncated dump arrives as data rather than as a parse
   throw."
  ^String [host]
  (normalize-dump (dump-tree-raw! host)))
