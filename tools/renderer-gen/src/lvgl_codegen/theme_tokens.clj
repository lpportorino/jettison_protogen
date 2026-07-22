(ns lvgl-codegen.theme-tokens
  "The theme's token→C projection — edn/tokens.edn is the home; this ns
   resolves the semantic tokens the native child theme consumes (src/theme.c)
   and emits `generated/theme_tokens.h`, the way `lvgl-codegen.gesture-thresholds`
   emits its header. Themed COLOR tokens emit a _DARK/_LIGHT pair; scalar
   tokens emit one define. The declared `fields` table is the closed contract:
   a renamed/removed token fails resolution loudly at generation time, never a
   silently-partial header, and `lvgl-codegen.theme-tokens-test` is the drift
   canary (committed header ≡ regenerated)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [lvgl-codegen.resolve :as resolve]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

(def edn-home "Repo-relative path to the EDN single source of truth." "edn/tokens.edn")

(def header-path
  "Repo-relative path to the generated C header (a projection of the home)."
  "generated/theme_tokens.h")

(def field-schema
  "One declared projection entry: the semantic token, its C macro base name,
   the resolution kind, and the trailing doc comment."
  [:map {:closed true} [:sem-key :keyword] [:c-macro [:string {:min 1}]]
   [:kind [:enum :color :radius :spacing :opacity]] [:doc [:string {:min 1}]]])

(def fields
  "The declared token↔projection mapping, in canonical emit order. Colors
   emit `<c-macro>_DARK` + `<c-macro>_LIGHT` (0xRRGGBB); scalars emit one
   integer define. The knob pad deliberately rides :spacing-xs until the
   knob-geometry spike replaces it with a measured token."
  [{:sem-key :surface-1
    :c-macro "THEME_SURFACE1"
    :kind :color
    :doc "panel/container background"}
   {:sem-key :edge-0 :c-macro "THEME_EDGE0" :kind :color :doc "panel/container border"}
   {:sem-key :fg-0 :c-macro "THEME_FG0" :kind :color :doc "primary text"}
   {:sem-key :focused-edge
    :c-macro "THEME_FOCUSED_EDGE"
    :kind :color
    :doc "FOCUS_KEY outline"}
   {:sem-key :disabled-fg
    :c-macro "THEME_DISABLED_FG"
    :kind :color
    :doc "disabled-state dim"}
   {:sem-key :radius-card
    :c-macro "THEME_RADIUS_PANEL"
    :kind :radius
    :doc "px: panel tier (cards, containers)"}
   {:sem-key :radius-default
    :c-macro "THEME_RADIUS_CONTROL"
    :kind :radius
    :doc "px: control tier (form controls, cb indicator)"}
   {:sem-key :radius-button
    :c-macro "THEME_RADIUS_BUTTON"
    :kind :radius
    :doc "px: buttons + buttonmatrix items"}
   {:sem-key :spacing-md
    :c-macro "THEME_PAD_PANEL"
    :kind :spacing
    :doc "px: panel padding (obj default, roller hor, table cells)"}
   {:sem-key :spacing-sm
    :c-macro "THEME_PAD_CONTROL"
    :kind :spacing
    :doc "px: control padding (button, textarea ver, btnmatrix)"}
   {:sem-key :spacing-xs
    :c-macro "THEME_PAD_GRID"
    :kind :spacing
    :doc "px: grid gaps (btnmatrix row/col)"}
   {:sem-key :spacing-none
    :c-macro "THEME_PAD_NONE"
    :kind :spacing
    :doc "px: true zero (host_proxy positioning frames)"}
   {:sem-key :border-width-default
    :c-macro "THEME_BORDER_W"
    :kind :spacing
    :doc "px: default border width"}
   {:sem-key :border-width-focus
    :c-macro "THEME_OUTLINE_W"
    :kind :spacing
    :doc "px: focus outline width + pad"}
   {:sem-key :disabled-opa
    :c-macro "THEME_DISABLED_OPA"
    :kind :opacity
    :doc "0-255: disabled-state opacity"}])

(defn- hex->c-literal
  "\"#12121F\" → \"0x12121F\" (the lv_color_hex argument form)."
  [hex]
  (str "0x" (str/upper-case (subs hex 1))))
(m/=> hex->c-literal [:=> [:cat [:string {:min 7 :max 7}]] [:string {:min 1}]])

(defn- resolve-field
  "Resolve one declared field against the token map → seq of [macro literal
   doc] emit rows. A color's themed {:dark :light} map emits the pair; every
   resolver throws loudly on an unknown/renamed token (the home boundary)."
  [tokens {:keys [sem-key c-macro kind doc]}]
  (case kind
    :color (let [{:keys [dark light]} (resolve/resolve-color tokens sem-key)]
             [[(str c-macro "_DARK") (hex->c-literal dark) doc]
              [(str c-macro "_LIGHT") (hex->c-literal light) doc]])
    :radius [[c-macro (str (resolve/resolve-radius tokens sem-key)) doc]]
    :spacing (if (str/starts-with? (name sem-key) "border-width")
               [[c-macro (str (resolve/resolve-border-width tokens sem-key)) doc]]
               [[c-macro (str (resolve/resolve-spacing tokens sem-key)) doc]])
    :opacity [[c-macro (str (resolve/resolve-opacity tokens sem-key)) doc]]))
(m/=> resolve-field
      [:=> [:cat [:map-of :keyword :any] field-schema]
       [:sequential [:tuple [:string {:min 1}] [:string {:min 1}] [:string {:min 1}]]]])

(defn- shadow-rows
  "The zeroed drop-shadow triple from :drop-card (opa/width/spread — the
   sharp-tactical shadow removal the theme applies to button/led)."
  [tokens]
  (let [{:keys [opa width spread]} (resolve/resolve-shadow tokens :drop-card)]
    [["THEME_DROP_OPA" (str (or opa 0)) "0-255: drop-shadow opacity (zeroed)"]
     ["THEME_DROP_W" (str (or width 0)) "px: drop-shadow width (zeroed)"]
     ["THEME_DROP_SPREAD" (str (or spread 0)) "px: drop-shadow spread (zeroed)"]]))
(m/=> shadow-rows
      [:=> [:cat [:map-of :keyword :any]]
       [:sequential [:tuple [:string {:min 1}] [:string {:min 1}] [:string {:min 1}]]]])

(defn load-tokens
  "Read the token home. The resolvers are the validation boundary — every
   declared field must resolve or generation throws with the token name."
  [home-path]
  (edn/read-string (slurp home-path)))
(m/=> load-tokens [:=> [:cat [:string {:min 1}]] [:map-of :keyword :any]])

(defn tokens->header
  "Emit the generated C header text for `tokens`: every declared field in
   canonical order (+ the shadow triple), wrapped
   in an include guard with the DO-NOT-EDIT preamble."
  [tokens]
  (let [rows (concat (mapcat #(resolve-field tokens %) fields) (shadow-rows tokens))]
    (str "/* GENERATED by lvgl-codegen.theme-tokens — DO NOT EDIT.\n"
         " * Regenerate: `make factory-bindings`. Home: edn/tokens.edn.\n"
         " * Native child-theme design tokens (src/theme.c): _DARK/_LIGHT\n"
         " * color pairs (0xRRGGBB for lv_color_hex) + scalar px/opa values.\n"
         " * The radius rule: panel tier 4px, control tier 2px — see the\n"
         " * :radii comment in the home. */\n"
         "#ifndef THEME_TOKENS_H\n#define THEME_TOKENS_H\n\n"
         (str/join "\n"
                   (for [[macro literal doc] rows]
                     (str "#define " macro " " literal " /* " doc " */")))
         "\n\n#endif /* THEME_TOKENS_H */\n")))
(m/=> tokens->header [:=> [:cat [:map-of :keyword :any]] [:string {:min 1}]])

(defn generate-header!
  "Write the generated C header (`generated/theme_tokens.h`) from the EDN
   home. Run via `make factory-bindings`; the mirror canary pins the
   committed header against the live home on `make test`."
  [home-path out-path]
  (spit out-path (tokens->header (load-tokens home-path)))
  nil)
(m/=> generate-header! [:=> [:cat [:string {:min 1}] [:string {:min 1}]] :nil])