(ns devcards.host-test
  "Canaries for `devcards.host`. Most need no live module; the engine-option
   tests below do — they build engines, and one runs a small hand-assembled
   wasm module through the SHARED engine, because the option's effect is only
   observable on a real trap.

   The first group is here for a coverage reason rather than a complexity one.
   `host/normalize-dump` is the membrane that turns a
   renderer-buffer overflow into data — without it, structurally cut JSON
   reaches the parser and the run dies before any lane can report
   :dump-truncated. Its only OTHER gate is the `dump-contracts` probe, which
   needs a built module and therefore runs in the renderer workflow; that
   workflow's `paths:` filter does not name `tools/devcards/**`. So a commit
   that deleted the clause and touched nothing outside this directory would
   start no workflow that runs the probe, and no corpus card overruns the
   buffer, so the corpus could not notice either.

   This suite runs under `devcards-test`, which the devcards workflow DOES run
   on exactly that push."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [devcards.host :as host]))

(def ^:private sentinel ",\"truncated\":true")

(deftest normalize-dump-substitutes-a-canonical-root-for-the-overflow-sentinel
  (testing "the renderer appends the sentinel over the TAIL of already-cut
            JSON, so the input is not parseable and the substitution — not the
            parser — is what has to notice"
    (let [cut (str "{\"type\":\"lv_obj\",\"children\":[{\"type\":\"lv_lab" sentinel)]
      (is (= "{\"truncated\":true,\"children\":[]}" (host/normalize-dump cut)))))
  (testing "and the output PARSES, which is the actual contract — the whole
            point is that the raw bytes do not. Asserting merely that the text
            `\"truncated\":true` appears would pass with the membrane deleted,
            since the sentinel the renderer appends contains that very string."
    (let [cut (str "{\"type\":\"lv_obj\",\"children\":[{\"type\":\"lv_lab" sentinel)]
      (is (thrown? Exception (json/read-str cut))
          "precondition: the raw dump is not parseable, so a pass-through
           membrane cannot satisfy the next assertion")
      (is (= {"truncated" true "children" []}
             (json/read-str (host/normalize-dump cut)))))))

(deftest normalize-dump-passes-an-untruncated-dump-through-BYTE-IDENTICAL
  (testing "the control. Without it a membrane hard-wired to return the
            canonical root would satisfy every assertion above while
            discarding every real tree in the corpus."
    (let [whole "{\"type\":\"lv_obj\",\"coords\":[0,0,99,99],\"children\":[]}"]
      (is (= whole (host/normalize-dump whole)))))
  (testing "a dump that merely CONTAINS the sentinel text without ending in it
            is not truncated — the check is anchored at the end on purpose,
            because a label's own text could otherwise trip it"
    (let [decoy (str "{\"type\":\"lv_label\",\"text\":\"" sentinel "\",\"children\":[]}")]
      (is (= decoy (host/normalize-dump decoy))))))

;; ── dump-draw-palette!: what ABSENCE means ──────────────────────────────────
;; These pin the two guard paths and NOTHING ELSE. The module path — a real
;; wasm, a real export, real bytes copied out of linear memory — is exercised
;; by no test here, because nothing consumes this fn yet; the probe that will
;; is the remaining half of the work. Saying so is the point: a reader must not
;; take these greens as evidence the export was ever successfully called.

(deftest dump-draw-palette-returns-nil-ONLY-for-a-module-without-the-export
  (testing "a wasm that predates the observer is a real state — a consumer pins
            its own build — and NIL is how it is reported, so the palette rule
            reaches its :observer-not-exposed reason and says out loud that it
            could not look"
    (is (nil? (host/dump-draw-palette!
               {:export? (constantly false)
                :call! (fn [& _]
                         (throw (AssertionError.
                                 "must not call an export it just found absent")))}))))
  (testing "the control: the probe is CONSULTED rather than ignored. Without
            this, a fn hard-wired to return nil would satisfy the assertion
            above while reporting every module as unobserved."
    (let [asked (atom [])]
      (try
        (host/dump-draw-palette! {:export? (fn [n] (swap! asked conj n) false)})
        (catch Exception _ nil))
      (is (= [host/draw-palette-export] @asked)))))

(deftest dump-draw-palette-THROWS-when-the-host-map-cannot-answer
  (testing "a host map with no :export? probe is a WIRING defect in the caller,
            not an answer about the module. Returning nil there would launder a
            broken caller into 'the observer is absent' and quietly mark the
            whole corpus unobserved — the one failure this repo refuses, a green
            that is indistinguishable from never having looked."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"no :export\? probe"
         (host/dump-draw-palette! {:call! (constantly nil)})))))

(deftest utf8-string-decodes-multi-byte-glyphs-rather-than-one-char-per-byte
  (testing "a DEGREE SIGN survives. The decoder used to append one char per
            byte — Latin-1 — so U+00B0 (UTF-8 c2 b0) became the two chars
            U+00C2 U+00B0 and was written back out as c3 82 c2 b0. That is the
            mangling measured in a consumer's generated pages, and this is the
            assertion that goes red if the byte loop reverts."
    (is (= "°" (host/utf8-string (byte-array [(unchecked-byte 0xc2)
                                              (unchecked-byte 0xb0)])))))
  (testing "and so does a PRIVATE-USE-AREA icon codepoint, which is the case
            that actually reaches an operator: the icon font's warning triangle
            U+F071 (UTF-8 ef 81 b1) became c3 af c2 81 c2 b1 and rendered in the
            page as a three-character mojibake before the label text."
    (is (= "" (host/utf8-string (byte-array [(unchecked-byte 0xef)
                                              (unchecked-byte 0x81)
                                              (unchecked-byte 0xb1)])))))
  (testing "CONTROL — ASCII is a FIXED POINT of the old bug, which is why it
            survived unnoticed. Without this case the two above could be
            satisfied by a decoder that mangles ordinary labels instead, and
            every label this corpus asserts on is ASCII."
    (is (= "Turn Off" (host/utf8-string (.getBytes "Turn Off" "UTF-8")))))
  (testing "the empty buffer is the empty string, not nil — read-cstring hits
            this on any node whose text is absent."
    (is (= "" (host/utf8-string (byte-array 0))))))

;; ── the shared engine's options ─────────────────────────────────────────────
;; The two tests in this section pin the PAIRING — the option is declared, and
;; the builder accepts it only because experimental options are allowed on the
;; ENGINE. They do not observe the option's EFFECT, because the polyglot API
;; exposes no getter for an engine's option values. The effect is pinned in the
;; next section, by a trap inside a WASI builtin run through the shared engine:
;; that builtin's frame appears only with the option applied, so deleting the
;; line that applies it reds that test.

(defn- built-engine
  "A fresh engine from the shared engine's own builder, or the refusal that
   builder threw — returned rather than thrown, so a refused build reads as an
   assertion FAILURE naming the refusal instead of an error in the test."
  []
  (try (#'host/new-engine)
       (catch IllegalArgumentException refused refused)))

(defn- engine-or-fail
  "Assert `built` is an Engine, naming the refusal when it is not."
  [built]
  (is (instance? org.graalvm.polyglot.Engine built)
      (str "the engine builder refused its own options: "
           (when (instance? Throwable built) (ex-message built))))
  (instance? org.graalvm.polyglot.Engine built))

(deftest the-shared-engine-builder-accepts-its-own-options
  (testing "the option is experimental, so the builder must allow experimental
            options on the ENGINE; dropping that half throws here — and at the
            first render — rather than silently building without the option"
    (let [built (built-engine)]
      (when (engine-or-fail built)
        (.close ^org.graalvm.polyglot.Engine built))))
  (testing "it declares the internal-frames option, and declares it ON"
    (is (= "true" (get host/engine-options "engine.ShowInternalStackFrames")))))

(deftest an-engine-option-is-refused-on-a-context-over-a-shared-engine
  (testing "the reason the option lives on the ENGINE builder and nowhere else:
            a context over a shared engine refuses an engine option outright"
    (let [built (built-engine)]
      (when (engine-or-fail built)
        (try
          (is (thrown-with-msg?
               IllegalArgumentException #"engine option"
               (-> (org.graalvm.polyglot.Context/newBuilder (into-array String ["wasm"]))
                   (.engine ^org.graalvm.polyglot.Engine built)
                   (.allowExperimentalOptions true)
                   (.option "engine.ShowInternalStackFrames" "true")
                   (.build))))
          (finally (.close ^org.graalvm.polyglot.Engine built)))))))

;; ── what the option makes VISIBLE, through the engine every render uses ──────

(defn- wasm-name
  "A wasm name: its length, then its UTF-8 bytes."
  [^String s]
  (let [bs (.getBytes s "UTF-8")]
    (into [(alength bs)] (map #(bit-and (int %) 0xff) bs))))

(defn- wasm-section
  "A wasm section: id, content length, content (every length here is < 128, so
   one LEB128 byte each)."
  [id content]
  (into [id (count content)] content))

(def ^:private wasi-trap-module
  "A minimal module whose export `badstat` calls the WASI builtin `fd_fdstat_get`
   with a result pointer past the end of memory, so the TRAP is raised inside
   the builtin — implementation code the engine marks internal — rather than in
   the module's own function. The previous fd_write fixture now returns WASI
   EFAULT in GraalWasm 25.4.4.1.1; it no longer exercises stack-frame retention."
  (byte-array
   (map unchecked-byte
        (concat [0x00 0x61 0x73 0x6d 0x01 0x00 0x00 0x00]
                (wasm-section 1 [2 0x60 2 0x7f 0x7f 1 0x7f 0x60 0 0])
                (wasm-section 2 (concat [1] (wasm-name "wasi_snapshot_preview1")
                                        (wasm-name "fd_fdstat_get") [0 0]))
                (wasm-section 3 [1 1])
                (wasm-section 5 [1 0 1])
                (wasm-section 7 (concat [2] (wasm-name "badstat") [0 1]
                                        (wasm-name "memory") [2 0]))
                (wasm-section 10 (let [body [0 0x41 1 0x41 0x70 0x10 0 0x1a 0x0b]]
                                   (concat [1 (count body)] body)))))))

(deftest the-shared-engine-keeps-the-frame-of-a-trap-inside-a-wasi-builtin
  (testing "a trap raised INSIDE an engine-provided builtin keeps the builtin's
            own frame in the polyglot trace. Without the internal-frames option
            that frame is filtered and the trace names only the caller, so a
            fault in, say, an asset read would point at the function that asked
            for it. Driven through the SHARED engine the renders use — so it
            needs the optimizing runtime this tool already requires."
    (let [built (try @@#'host/shared-engine
                     (catch IllegalArgumentException refused refused))]
      (when (engine-or-fail built)
        (with-open [ctx (-> (org.graalvm.polyglot.Context/newBuilder (into-array String ["wasm"]))
                            (.engine ^org.graalvm.polyglot.Engine built)
                            (.allowExperimentalOptions true)
                            (.option "wasm.Builtins" "wasi_snapshot_preview1")
                            (.build))]
          (let [module (.eval ctx (.build (org.graalvm.polyglot.Source/newBuilder
                                           "wasm"
                                           (org.graalvm.polyglot.io.ByteSequence/create
                                            ^bytes wasi-trap-module)
                                           "wasi-trap")))
                inst (.newInstance module (object-array 0))
                exports (if (.hasMember inst "exports") (.getMember inst "exports") inst)
                frames (try (.execute (.getMember exports "badstat") (object-array 0))
                            nil
                            (catch org.graalvm.polyglot.PolyglotException p
                              (mapv str (.getStackTrace p))))]
            (is (some? frames) "precondition: the out-of-bounds write traps")
            (is (some #(re-find #"__wasi_fd_fdstat_get" %) frames)
                (str "the builtin's frame is missing from " (pr-str (take 4 frames))))
            (testing "control: the module's OWN frame is present either way, so a
                    missing builtin frame is the filter and not an empty trace"
              (is (some #(re-find #"badstat" %) frames)))))))))
