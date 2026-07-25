(ns renderer-gen.design-tokens-json
  "Emit `design-tokens.json` — the FULLY-RESOLVED design-token manifest
   (a ratified home-move decision: protogen owns tokens.edn + the resolver
   and emits the manifest; consumer authoring reads it from the pin, so no
   second resolver copy ever exists).

   Shape (CLOSED — a consumer can rely on every key):
     {\"source\": <the tokens.edn home, repo-relative>,
      \"tokens\": {<semantic-token> {\"kind\": color|font|spacing|radius|
                                     shadow|opacity|border-width,
                                     \"dark\": <concrete>, \"light\": <concrete>}}}
   Every semantic token is CONCRETE per mode — an invariant token carries the
   same value under both keys, so consumers never re-implement the
   themed-vs-invariant distinction. Colors are \"#RRGGBB\" strings, fonts the
   LVGL C symbol name, spacing/radius/border-width pixel ints, opacity 0-255
   ints, shadows {opa,width,spread} maps.

   The `kinds` table below is the closed classification (the declared-fields
   pattern of lvgl-codegen.theme-tokens): a token added to tokens.edn without
   a classification here — or a classified token missing from tokens.edn —
   FAILS the emit loudly. Resolution itself is delegated to
   lvgl-codegen.resolve (the one resolver), which throws on any dangling
   semantic→primitive reference; a nil surviving into the manifest is a bug,
   so the emitter re-checks and throws.

   Run (from tools/renderer-gen/):
     clojure -M -m renderer-gen.design-tokens-json \\
       --tokens edn/tokens.edn --output ../../output/manifests/design-tokens.json"
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [lvgl-codegen.resolve :as resolve])
  (:gen-class))

(set! *warn-on-reflection* true)

(def manifest-source
  "The tokens.edn home the manifest is derived from, as protogen consumers
   see it."
  "tools/renderer-gen/edn/tokens.edn")

(def kinds
  "The closed semantic-token classification: every `:semantic` key in
   tokens.edn, mapped to the resolver that concretizes it. Complete in both
   directions (checked by `check-classification!`)."
  {;; ── surfaces / foreground / edges / accent / status / interactive ──
   :surface-0 :color
   :surface-1 :color
   :surface-2 :color
   :surface-overlay :color
   :fg-0 :color
   :fg-1 :color
   :fg-2 :color
   :edge-0 :color
   :edge-1 :color
   :accent-bg :color
   :accent-text :color
   :media-accent :color
   :status-success :color
   :status-warning :color
   :status-error :color
   :pressed-surface :color
   :pressed-accent :color
   :focused-edge :color
   :checked-accent :color
   :disabled-fg :color
   :hud-border :color
   ;; ── typography ──
   :font-caption :font
   :font-body-sm :font
   :font-body :font
   :font-body-lg :font
   :font-data :font
   :font-ttf-probe :font
   :font-heading :font
   :font-heading-lg :font
   :font-heading-xl :font
   ;; ── spacing ──
   :spacing-xs :spacing
   :spacing-sm :spacing
   :spacing-md :spacing
   :spacing-lg :spacing
   :spacing-xl :spacing
   :spacing-none :spacing
   ;; ── radii ──
   :radius-default :radius
   :radius-button :radius
   :radius-card :radius
   :radius-none :radius
   ;; ── shadows ──
   :drop-card :shadow
   :drop-overlay :shadow
   ;; ── opacity ──
   :overlay-opa :opacity
   :disabled-opa :opacity
   :zero-opa :opacity
   :hud-scrim-opa :opacity
   :hud-pressed-opa :opacity
   ;; ── border widths ──
   :border-width-default :border-width
   :border-width-focus :border-width
   :border-width-bold :border-width})

(defn check-classification!
  "Fail loud unless the `kinds` table and tokens.edn's `:semantic` map cover
   exactly the same key set — a new/renamed token must be classified here
   before it can ship in the manifest."
  [tokens]
  (let [semantic (set (keys (:semantic tokens)))
        classified (set (keys kinds))
        unclassified (sort (remove classified semantic))
        orphaned (sort (remove semantic classified))]
    (when (or (seq unclassified) (seq orphaned))
      (throw (ex-info (str "design-tokens classification drift — "
                           "unclassified semantic tokens: " (vec unclassified)
                           "; classified but absent from tokens.edn: " (vec orphaned))
                      {:unclassified (vec unclassified) :orphaned (vec orphaned)})))))

(defn- themed
  "Normalize a resolver result to the uniform {:dark v :light v} pair: a
   {:dark … :light …} map passes through, anything else (an invariant value)
   is duplicated into both modes."
  [v]
  (if (and (map? v) (contains? v :dark) (contains? v :light)) v {:dark v :light v}))

(defn- shadow-json
  "One concrete shadow value as its closed JSON map."
  [{:keys [opa width spread] :as shadow}]
  (when-not (and (int? opa) (int? width) (int? spread))
    (throw (ex-info "shadow value is not a concrete {opa,width,spread} map"
                    {:shadow shadow})))
  {"opa" opa "width" width "spread" spread})

(defn resolve-token
  "Resolve one classified semantic token to its uniform manifest entry
   {:kind … :dark … :light …}, every value concrete. Throws (via
   lvgl-codegen.resolve, or here) on any dangling reference."
  [tokens sem-key kind]
  (let [{:keys [dark light]} (themed (case kind
                                       :color (resolve/resolve-color tokens sem-key)
                                       :font (resolve/resolve-font tokens sem-key)
                                       :spacing (resolve/resolve-spacing tokens sem-key)
                                       :radius (resolve/resolve-radius tokens sem-key)
                                       :shadow (resolve/resolve-shadow tokens sem-key)
                                       :opacity (resolve/resolve-opacity tokens sem-key)
                                       :border-width
                                       (resolve/resolve-border-width tokens sem-key)))]
    (when (or (nil? dark) (nil? light))
      (throw (ex-info (str "semantic token " sem-key
                           " resolved to nil — "
                           "a dangling primitive reference the manifest must not carry")
                      {:token sem-key :kind kind :dark dark :light light})))
    {:kind kind
     :dark (if (= :shadow kind) (shadow-json dark) dark)
     :light (if (= :shadow kind) (shadow-json light) light)}))

(defn manifest
  "The full manifest map (JSON-ready, deterministically ordered)."
  [tokens]
  (check-classification! tokens)
  {"source" manifest-source
   "tokens" (into (sorted-map)
                  (map (fn [[sem-key kind]]
                         (let [{:keys [dark light] k :kind}
                               (resolve-token tokens sem-key kind)]
                           [(name sem-key) {"kind" (name k) "dark" dark "light" light}])))
                  kinds)})

(defn- parse-args
  "Closed --tokens/--output flag pairs; unknown flags fail loud."
  [args]
  (reduce (fn [acc [flag value]]
            (case flag
              "--tokens" (assoc acc :tokens value)
              "--output" (assoc acc :output value)
              (throw (ex-info (str "unknown flag: " flag)
                              {:flag flag :expected #{"--tokens" "--output"}}))))
          {:tokens "edn/tokens.edn"}
          (partition 2 args)))

(defn -main
  "Read tokens.edn, emit the fully-resolved design-tokens.json, print the
   token count. `--output` is mandatory — this tool never guesses a
   destination."
  [& args]
  (let [{:keys [tokens output]} (parse-args args)]
    (when-not output (throw (ex-info "--output is required" {:args (vec args)})))
    (let [tokens-map (edn/read-string (slurp tokens))
          m (manifest tokens-map)]
      (io/make-parents output)
      (with-open [w (io/writer output)]
        (json/write m w :indent true :escape-slash false)
        (.write w "\n"))
      (println (str "design-tokens.json: " (count (get m "tokens"))
                    " semantic tokens resolved (dark+light) -> " output)))))