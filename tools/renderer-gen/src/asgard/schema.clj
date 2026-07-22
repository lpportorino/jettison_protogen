(ns asgard.schema
  "Video event schemas + named Malli primitives.

   Named primitives are plain vars (pass-by-var) rather than a global
   registry: a `set-default-registry!` install mutates JVM-wide schema
   resolution for every namespace, load-order dependent — these namespaces
   reference schemas across namespaces by var instead, keeping resolution local
   and explicit. Every
   primitive carries an `:error/fn` (value-bearing) or `:error/message`
   property so `malli.error/humanize` produces an actionable message —
   'expected non-empty string, got 42' rather than an anonymous
   'should be at least 1 character'."
  (:require [asgard.video-config :as video-config]
            [clojure.string :as str]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

;; ── Error helpers ──
(defn- expected-fn
  "Return a Malli `:error/fn` value that produces
   'expected <noun>, got <pr-str-of-value>' on validation failure.
   pr-str output is truncated to 80 chars to keep diagnostics readable
   on long values (e.g. nested maps caught at body validation)."
  [noun]
  (fn [{:keys [value]} _]
    (let [s (pr-str value)
          s' (if (> (count s) 80) (str (subs s 0 77) "...") s)]
      (str "expected " noun ", got " s'))))
(m/=> expected-fn [:=> [:cat [:string {:min 1}]] fn?])

;; ── Named primitives ──
(def ne-string
  "Non-empty string. Use for identifiers, names, and any string input
   where the empty string is a bug."
  [:string {:min 1 :error/fn (expected-fn "non-empty string")}])

(def kw-map
  "Open map with keyword keys. Values are intentionally `:any` — the
   keyword-key constraint IS the tighten over a bare `:map`."
  [:map-of {:error/fn (expected-fn "map with keyword keys")} :keyword :any])

;; ── Video event schemas ──
(def video-event-source
  [:enum {:error/fn (expected-fn "video event source (\"gpu\")")} "gpu"])

(def stream-name
  "Enum of the configured video streams — DERIVED from the asgard.video-config
   keys (the one home), so adding a stream there updates this automatically."
  (into [:enum
         {:error/fn (expected-fn (str "known stream name ("
                                      (str/join " " (sort (keys video-config/streams)))
                                      ")"))}]
        (sort (keys video-config/streams))))

(def video-event
  "Discriminated union of video window events, keyed on :type."
  [:multi {:dispatch :type}
   ["lifecycle"
    [:map [:type [:= "lifecycle"]] [:state [:enum "initializing" "ready" "closed" "error"]]
     [:detail {:optional true} :string]]]
   ["fps" [:map [:type [:= "fps"]] [:video :double] [:osd :double] [:render :double]]]
   ["pipeline-stats"
    [:map [:type [:= "pipeline-stats"]] [:ingress kw-map] [:protocol kw-map]
     [:renderer kw-map]]]
   ["gpu-ready"
    [:map [:type [:= "gpu-ready"]] [:gpuName ne-string] [:backend ne-string]
     [:deviceType ne-string]]]
   ["decoder-ready" [:map [:type [:= "decoder-ready"]] [:codec ne-string]]]
   ["thread-stopped"
    [:map [:type [:= "thread-stopped"]] [:thread ne-string] [:reason :string]]]
   ["window-moved" [:map [:type [:= "window-moved"]] [:x :int] [:y :int]]]
   ["window-resized" [:map [:type [:= "window-resized"]] [:width :int] [:height :int]]]
   ["warning" [:map [:type [:= "warning"]] [:code ne-string] [:message :string]]]])

(def video-event-envelope
  [:map [:source video-event-source] [:stream stream-name] [:timestamp ne-string]
   [:event video-event]])

;; ── Log event schema (frontend → backend log streaming) ──
(def log-source
  [:enum {:error/fn (expected-fn "log source (app-main or app-video)")} "app-main"
   "app-video"])

(def log-level
  [:enum {:error/fn (expected-fn "log level (error warn info debug)")} "error" "warn" "info"
   "debug"])

(def log-event-envelope
  "A frontend log line forwarded to POST /events/log — re-emitted to the
   backend's stdout (joins the downstream log-ingest chain) and kept in the /debug
   ring for local/dev."
  [:map [:source log-source] [:level log-level] [:logger ne-string] [:message ne-string]
   ;; full stack trace (uncaught error / unhandled rejection / Error arg) — a
   ;; first-class typed key (not buried in :fields) so the SQL column + the
   ;; logs-analyst recipes can target it.
   [:stack {:optional true} [:string {:min 1}]] [:timestamp ne-string]
   [:stream {:optional true} stream-name] [:fields {:optional true} kw-map]])