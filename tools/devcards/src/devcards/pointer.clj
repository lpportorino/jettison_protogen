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
   controls_tick, so every gesture step is followed by settle ticks."
  (:require [clojure.data.json :as json]
            [devcards.host :as host])
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
   interaction probe wants to KNOW). Returns the rc."
  [{:keys [push! w h] :as _host} phase [px py] t]
  (let [[nx ny] (px->ndc [px py] {:w w :h h})
        rc (push! "controls_host_message"
                  (pointer-bytes {:phase phase :ndc-x nx :ndc-y ny :t t}))]
    (when-not (zero? (long rc))
      (throw (ex-info "controls_host_message rc != OK" {:rc rc :phase phase :px [px py]})))
    rc))

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

(defn events
  "Decode every captured host_event envelope
   ({\"v\":1,\"tag\":...,\"origin\":...,\"event\":...,\"seq\":...,\"value\":...})."
  [{:keys [captured] :as _host}]
  (mapv #(json/read-str (String. ^bytes % "UTF-8") :key-fn keyword) (:events @captured)))

(defn events-tagged
  "The captured envelopes whose :tag equals `tag`, in emission order."
  [h tag]
  (filterv #(= tag (:tag %)) (events h)))

(defn clear-events!
  "Reset the captured host_event lane between probe assertions."
  [{:keys [captured] :as _host}]
  (swap! captured assoc :events []))