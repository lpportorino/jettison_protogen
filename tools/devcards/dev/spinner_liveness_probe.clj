(ns spinner-liveness-probe
  "Empirical probe, gating nothing: can the corpus's committed goldens tell a
   LIVE `lv_spinner` from a DEAD one?

   A golden here is a sha256 over one raw framebuffer, rendered under the
   PINNED protocol (`devcards.host/render-ticks` ticks of
   `devcards.host/tick-ms`). An animation is a function of accumulated tick
   time, so whether that single frame carries any animation phase at all is a
   property of the protocol, not something a reader can settle from the
   sources. It is also not visible anywhere else: `dump_obj` emits no arc
   angle and no animation phase, so the framebuffer is the only surface on
   which a stopped animation could ever appear.

   HOW `DEAD` IS MODELLED, and why this needs no C mutation and no rebuild.
   The renderer's spinner arm calls `lv_spinner_set_anim_params(obj,
   p->spin_time, p->arc_length)` with the card's AUTHORED spin_time, and LVGL
   starts both arc animations at fixed values (start angle 0, end angle
   `arc_length`) and interpolates toward them over that duration. An authored
   spin_time far longer than any schedule here therefore holds both
   animations at exactly their t=0 values for the whole render — the state a
   renderer that stopped animating would show, reached through the same code
   path, with no byte of `renderer/` or `renderer/lvgl/` touched.

   THREE MEASUREMENTS, and the second and third exist because the first alone
   would license two wrong repairs:

     corpus-blindness  every lv_spinner card, both modes: the COMMITTED
                       golden, the card as authored, and the card held at
                       t=0. The as-authored column is the CONTROL — if it
                       does not reproduce the committed hash this probe is
                       not observing the baseline and the other columns mean
                       nothing.
     tick-threshold    one card across a range of tick budgets. Reports the
                       budget at which a live render first parts from a held
                       one — and, just as importantly, any LARGER budget at
                       which the two RE-CONVERGE, since a repeating animation
                       aliases back to its start phase and a fixed settle
                       that lands near a whole number of revolutions is
                       vacuously green.
     spin-sweep        the PINNED budget across a range of authored
                       spin_times. This is what separates a protocol-level
                       blindness from an authoring-level one, and therefore
                       whether re-authoring a card could close anything.

   NON-VACUITY: an identity verdict over two blank frames measures nothing,
   so every rendered frame reports its distinct-pixel count alongside its
   hash and a reader can refuse a row that carries one value.

   Prints a table per measurement and exits 0 either way: this is an
   instrument, not a gate, and nothing here judges the tree."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [devcards.fixtures :as fixtures]
            [devcards.golden :as golden]
            [devcards.host :as host]))

(set! *warn-on-reflection* true)

(def ^:private canvas
  "The protocol canvas, mirroring `devcards.core/render-protocol`."
  {:w 800 :h 480})

(def ^:private held-spin-time
  "An authored spin time longer than every schedule below, so both arc
   animations stay at their t=0 values for the whole render."
  100000000)

(def ^:private probe-card
  "The card the two sweeps drive — the largest sized spinner, so a phase
   difference has the most pixels available to show itself in."
  "lv_spinner/default/large")

(defn- boot!
  "A fresh host over the pinned wasm and assets at the protocol canvas."
  []
  (host/start! {:wasm "../../renderer/output/controls.wasm"
                :assets "../../renderer/assets"
                :w (:w canvas)
                :h (:h canvas)}))

(defn- render-ticks!
  "`render-card!`'s protocol with the tick COUNT made a parameter: the same
   call order and the same tick size, `n` ticks instead of the pinned budget.
   Returns the raw framebuffer."
  ^bytes [^bytes pb dark n]
  (let [h (boot!)]
    (try
      (host/set-breakpoint! h 0)
      (host/set-theme-dark! h (if dark 1 0))
      (host/set-dpi! h host/default-dpi)
      (host/load-ui! h pb)
      (dotimes [_ n] (host/tick! h host/tick-ms))
      (host/read-framebuffer! h)
      (finally (host/close! h)))))

(defn- distinct-px
  "How many distinct RGBA values the frame carries — the non-vacuity floor."
  ^long [^bytes fb]
  (count (into #{} (map vec) (partition 4 (seq fb)))))

(defn- differing-px
  "How many pixels differ between two equally-sized frames."
  ^long [^bytes a ^bytes b]
  (count (remove true? (map = (partition 4 (seq a)) (partition 4 (seq b))))))

(defn- short-sha
  "First 10 hex characters of a frame's sha256 — enough to compare by eye."
  ^String [^bytes fb]
  (subs (golden/sha256-hex fb) 0 10))

(defn- with-spin-time
  "`spec` with every lv_spinner card's authored spin_time replaced by `t`.
   Touches nothing else, so the built bytes differ in that field alone."
  [spec t]
  (letfn [(card [c] (cond-> c
                      (get-in c [:props :spinner_props])
                      (assoc-in [:props :spinner_props :spin_time] t)))
          (widget [w] (cond-> w
                        (= "lv_spinner" (:tag w)) (update :cards #(mapv card %))))]
    (update spec :widgets #(mapv widget %))))

(defn- spinner-cards
  "Every built entry whose id names an lv_spinner card, in spec order."
  [spec]
  (filterv #(.startsWith ^String (str (:id %)) "lv_spinner/")
           (fixtures/build-all spec)))

(defn- card-bytes
  "The built Screen bytes for `probe-card` under `spec`."
  ^bytes [spec]
  (:bytes (first (filter #(= probe-card (str (:id %))) (spinner-cards spec)))))

(defn- committed-goldens
  "label -> {card-id -> sha256} read from the committed manifests."
  []
  {:dark (:cards (edn/read-string (slurp (io/file "goldens/manifest-dark.edn"))))
   :light (:cards (edn/read-string (slurp (io/file "goldens/manifest-light.edn"))))})

(defn- blindness-row
  "One card × mode row: the committed golden, the as-authored render (the
   CONTROL) and the held-at-t0 render, with each render's pixel variety."
  [manifest ^bytes live-pb ^bytes held-pb id mode dark]
  (let [^bytes live (render-ticks! live-pb dark host/render-ticks)
        ^bytes held (render-ticks! held-pb dark host/render-ticks)
        gold (get-in manifest [id :sha256])]
    (println (format "%-34s %-5s %-11s %-11s %-11s %-14s %s"
                     id mode
                     (if gold (subs gold 0 10) "MISSING")
                     (short-sha live)
                     (short-sha held)
                     (if (= gold (golden/sha256-hex live)) "control=OK" "control=BAD")
                     (format "held-vs-golden-differing-px=%d px-variety[live=%d held=%d]"
                             (differing-px live held)
                             (distinct-px live)
                             (distinct-px held))))))

(defn- run-corpus-blindness!
  "MEASUREMENT 1 — every lv_spinner card, both modes."
  [spec]
  (let [live (spinner-cards spec)
        held (into {} (map (juxt #(str (:id %)) :bytes)) (spinner-cards (with-spin-time spec held-spin-time)))
        gold (committed-goldens)]
    (when (empty? live)
      (throw (ex-info "no lv_spinner cards built — the probe would be vacuous" {})))
    (println "\n== 1. corpus blindness: committed golden vs live vs held-at-t0 ==")
    (println (format "protocol: %d ticks x %d ms" host/render-ticks host/tick-ms))
    (doseq [{:keys [id] ^bytes pb :bytes} live
            [mode dark] [["dark" true] ["light" false]]]
      (blindness-row (get gold (if dark :dark :light)) pb (get held (str id)) (str id) mode dark))))

(defn- run-tick-threshold!
  "MEASUREMENT 2 — one card across tick budgets, live against held."
  [spec budgets]
  (let [^bytes live-pb (card-bytes spec)
        ^bytes held-pb (card-bytes (with-spin-time spec held-spin-time))]
    (println (str "\n== 2. tick threshold on " probe-card " (dark) =="))
    (println (format "%-7s %-9s %-11s %-11s %s" "ticks" "tick-ms" "live" "held" "differing-px"))
    (doseq [n budgets]
      (let [^bytes live (render-ticks! live-pb true n)
            ^bytes held (render-ticks! held-pb true n)]
        (println (format "%-7d %-9d %-11s %-11s %d"
                         n (* (long n) (long host/tick-ms))
                         (short-sha live) (short-sha held) (differing-px live held)))))))

(defn- run-spin-sweep!
  "MEASUREMENT 3 — the PINNED budget across authored spin_times."
  [spec spin-times]
  (let [^bytes held (render-ticks! (card-bytes (with-spin-time spec held-spin-time))
                                   true host/render-ticks)]
    (println (str "\n== 3. authored spin_time sweep at the PINNED budget, " probe-card " (dark) =="))
    (println (format "%-11s %-11s %s" "spin_time" "sha" "differing-px-vs-held"))
    (doseq [t spin-times]
      (let [^bytes fb (render-ticks! (card-bytes (with-spin-time spec t)) true host/render-ticks)]
        (println (format "%-11d %-11s %d" t (short-sha fb) (differing-px fb held)))))))

(defn -main
  "Run all three measurements against the committed corpus and manifests."
  [& _]
  (let [spec (fixtures/load-spec)]
    (run-corpus-blindness! spec)
    (run-tick-threshold! spec [1 2 3 4 5 6 8 12 16 24 32 48 64 96 128])
    (run-spin-sweep! spec [16 32 48 64 100 200 500 1000])
    (println "\nprobe complete - it judges nothing; read the columns.")))
