(ns spike-r22
  "Cross-engine determinism probe, GraalWasm side: renders the 4 probe
   fixtures × light/dark under the pinned protocol (host/render-card!),
   writes raw framebuffers to out/fb-probe/ for shell-side sha256 comparison,
   and prints per-card wall time.

   A HAND-RUN SPIKE, WIRED INTO NOTHING, and that is what `dev/` means here.
   Its Rust counterpart `wasm_harness/tests/fb_hash_probe.rs` — which this
   docstring used to name — was deleted: it sat in `tests/` where it read as
   a gate while no lane ever compiled it. The STANDING, automated form of the
   proof this spike approximates is `composition_cross_engine_fb`
   (wasm_harness/tests/composition_interaction.rs), which asserts each
   composition card's raw framebuffer byte-identical between the two engines
   on every battery run. Reach for this file when a NEW ABI revision needs the
   4 atomic probe fixtures dumped by hand; do not mistake it for coverage."
  (:require [clojure.java.io :as io]
            [devcards.host :as host]))

(def ^:private spike-fixtures ["vr_button" "vr_slider" "vc_clip" "vr_svg_icon"])

(defn -main
  [& _]
  (let [ws "../../renderer"
        h
        (host/start!
         {:wasm (str ws "/output/controls.wasm") :assets (str ws "/assets") :w 480 :h 320})]
    (io/make-parents "out/fb-probe/x")
    (doseq [fixture spike-fixtures
            dark [0 1]]
      (let [pb (java.nio.file.Files/readAllBytes (java.nio.file.Path/of
                                                  (str ws "/output/fixtures/" fixture ".pb")
                                                  (into-array String [])))
            t0 (System/nanoTime)
            fb (host/render-card! h {:pb pb :bp 0 :dark dark})
            ms (/ (- (System/nanoTime) t0) 1e6)]
        (with-open [out (io/output-stream (str "out/fb-probe/" fixture "_dark" dark ".raw"))]
          (.write out ^bytes fb))
        (println (format "%s_dark%d: %d bytes, %.1f ms" fixture dark (count fb) ms))))
    (host/close! h)
    (println "SPIKE DUMPS DONE")))