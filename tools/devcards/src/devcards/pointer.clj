(ns devcards.pointer
  "Pointer-input driver for the GraalWasm devcards host — the interaction
   half of the runner: encodes ui.HostToWasm pointer events, pushes them
   through `controls_host_message` on a devcards.host instance, and
   decodes the captured host_event envelopes, so seek/drag/tap round-trips
   run in-process on the JVM engine (mirroring the native harness's
   interaction lane).

   The px→NDC mapping inverts the renderer's ndc_to_px (x = (ndc+1)/2*w,
   y = (1-ndc)/2*h — +y is UP in NDC, so the y axis flips). Ticks are the
   caller's job: LVGL's indev polls the synced pointer globals on the next
   controls_tick, so every gesture step is followed by settle ticks.

   This ns is the input-INJECTION half of the probe surface and needs the
   compiled ui.UiInput bindings; the capture-READING half (events /
   events-tagged / clear-events!) lives in `devcards.probe`, which stays
   loadable without bindings."
  (:require [devcards.host :as host])
  (:import [ui UiInput$HostToWasm UiInput$PointerEvent UiInput$PointerKind
            UiInput$PointerPhase]))

(set! *warn-on-reflection* true)

(def schema-version
  "UI_INPUT_SCHEMA_VERSION — the renderer's fail-fast envelope guard; a
   version the module does not speak is rejected at the boundary."
  1)

(def ^:private phases
  {:down UiInput$PointerPhase/POINTER_PHASE_DOWN
   :move UiInput$PointerPhase/POINTER_PHASE_MOVE
   :up UiInput$PointerPhase/POINTER_PHASE_UP
   :cancel UiInput$PointerPhase/POINTER_PHASE_CANCEL})

(defn px->ndc
  "Framebuffer px point -> [ndc-x ndc-y] for a w×h canvas (inverse of the
   renderer's ndc_to_px; +y UP, so the y axis flips)."
  [[px py] {:keys [w h]}]
  [(- (/ (* 2.0 (long px)) (long w)) 1.0) (- 1.0 (/ (* 2.0 (long py)) (long h)))])

(defn ndc->px
  "[ndc-x ndc-y] -> the framebuffer px point for a w×h canvas — the renderer's
   OWN ndc_to_px (main.c), mirrored: `(ndc+1)/2*w` and `(1-ndc)/2*h`, each
   truncated by a C cast to int32. Both products are non-negative over NDC
   [-1,1], so the truncation is a floor.

   The inverse of px->ndc above and the round-trip partner it is kept beside: a
   probe that reads an NDC value OUT of the renderer (a gesture command's
   corners) and compares it against something the renderer DREW needs to travel
   the same way the renderer did, or the comparison measures the rounding rather
   than the contract."
  [[nx ny] {:keys [w h]}]
  [(long (* (+ (double nx) 1.0) 0.5 (long w)))
   (long (* (- 1.0 (double ny)) 0.5 (long h)))])

(defn pointer-bytes
  "One HostToWasm{pointer} message, serialized."
  ^bytes [{:keys [phase pointer-id ndc-x ndc-y t]}]
  (-> (UiInput$HostToWasm/newBuilder)
      (.setVersion (int schema-version))
      (.setPointer (-> (UiInput$PointerEvent/newBuilder)
                       (.setPhase ^UiInput$PointerPhase (get phases phase))
                       (.setKind UiInput$PointerKind/POINTER_KIND_TOUCH)
                       (.setPointerId (int (or pointer-id 1)))
                       (.setX (double ndc-x))
                       (.setY (double ndc-y))
                       (.setEventTime (long t))
                       .build))
      .build
      .toByteArray))

(defn pointer!
  "Push one pointer event at px point `[px py]`; throws on a non-OK rc
   (0 = OK; a positive rc is a benign no-op class, still surfaced — an
   interaction probe wants to KNOW). Returns the rc.

   `pointer-id` defaults to 1. A probe that drives a SECOND contact — a pinch,
   or any assertion about what a second finger reaches — must give it its own
   id, or the renderer reads the duplicate as an idempotent re-seat of the first
   and reports HOSTMSG_RESEAT, which this fn then throws on."
  ([host phase pt t] (pointer! host phase pt t 1))
  ([{:keys [push! w h] :as _host} phase [px py] t pointer-id]
   (let [[nx ny] (px->ndc [px py] {:w w :h h})
         rc (push! "controls_host_message"
                   (pointer-bytes {:phase phase :pointer-id pointer-id
                                   :ndc-x nx :ndc-y ny :t t}))]
     (when-not (zero? (long rc))
       (throw (ex-info "controls_host_message rc != OK"
                       {:rc rc :phase phase :px [px py] :pointer-id pointer-id})))
     rc)))

(defn settle!
  "Advance `n` ticks of `ms` each (indev poll + timers + render)."
  [h n ms]
  (dotimes [_ n] (host/tick! h ms)))

(defn tap!
  "A full tap at px point: DOWN, settle, UP, settle. `t0` seeds the event
   clock (ms, must be >0)."
  [h pt t0]
  (pointer! h :down pt t0)
  (settle! h 4 16)
  (pointer! h :up pt (+ (long t0) 80))
  (settle! h 4 16))

(defn drag!
  "A full drag: DOWN at `from`, MOVE through each of `via` (settling after
   every step), UP at the last point. `t0` seeds the event clock; steps
   advance it 50 ms apart."
  [h from via t0]
  (pointer! h :down from t0)
  (settle! h 3 16)
  (let [tend (reduce (fn [t pt]
                       (let [t (+ (long t) 50)]
                         (pointer! h :move pt t)
                         (settle! h 3 16)
                         t))
                     t0
                     via)]
    (pointer! h :up (or (last via) from) (+ (long tend) 50))
    (settle! h 3 16)))