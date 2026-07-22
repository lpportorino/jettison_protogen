(ns spike-r22
  "Cross-engine determinism probe, GraalWasm side (the standing counterpart
   of the wasm harness's fb_hash_probe): renders the 4 probe fixtures ×
   light/dark under the pinned protocol (host/render-card!), writes raw
   framebuffers to out/fb-probe/ for shell-side sha256 comparison with the
   Rust probe's dumps, and prints per-card wall time."
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