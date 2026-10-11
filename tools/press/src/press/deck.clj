(ns press.deck
  "Self-contained HTML slide decks from data.

     render   — a deck (EDN: title, palette, slides as hiccup with asset
                forms) → ONE HTML string with every asset, the theme and the
                font inlined as data: URIs
     check    — the offline gate over an HTML string: every external
                reference it still carries, what was inlined, the bytes
     build!   — render, check, REFUSE to write when anything is external or a
                palette pair is under its contrast floor, else write one file

   The rule: a deck is one file that opens from `file://` on any machine
   with nothing beside it — no relative or absolute paths, no CDN. `build!`
   makes a failing deck impossible to produce rather than merely reported.
   Slides are data, so the same EDN can feed another renderer; the theme is
   a dark house style whose palette is held to numeric WCAG contrast floors.

   Asset forms inside a slide body, expanded before rendering:
     [:deck/img path caption?]     [:deck/vid path caption?]
     [:deck/card title body kind?] [:deck/grid cols & items]
     [:deck/tbl headers rows]      [:deck/ul & items]
     [:deck/raw html-string]       (trusted markup, not escaped)
   `path` is resolved against the deck's `:root`; the same file used twice
   is inlined ONCE and re-pointed in the page (`data-from`). Video must be a
   codec the offline checker can decode — the Playwright image's Chromium has
   no H.264, so AV1 or VP9; `build!` refuses `.mp4`/`.mov` unless the deck
   sets `:allow-h264? true` and accepts that the shooter cannot see them."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.math :as math]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [hiccup2.core :as h]
            [malli.core :as m]
            [press.config :as config])
  (:import [java.io File]
           [java.nio.file Files StandardCopyOption]
           [java.util Base64]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------- palette

(def dark-palette
  "The dark house palette: near-black ground (`:bg`), two dark panels,
   two rule colours, white primary text (`:ink`), dimmed grey secondary
   (`:mut`, `:dim`) and five accents — `:amber`, `:slate`, `:cad` (green),
   `:cyan` and `:bad` (red)."
  {:bg "#0F1116" :panel "#191D26" :panel2 "#232833"
   :line "#2E3542" :line2 "#3D4655"
   :ink "#F2F4F8" :mut "#B0BACA" :dim "#8D97A6"
   :amber "#FFC45C" :slate "#96ACCD" :cad "#7EE6A8" :cyan "#96E2F0" :bad "#FF8282"})

(def ^:private hex-colour [:re #"^#[0-9A-Fa-f]{6}$"])

(def palette-spec
  "Every key the theme CSS references, each a 6-digit hex colour."
  (into [:map] (map (fn [k] [k hex-colour])) (keys dark-palette)))

(def contrast-floors
  "WCAG floors per foreground role on each of the three grounds: body text
   ≥ 7:1 (AAA), everything else that carries meaning ≥ 4.5:1 (AA)."
  {:ink 7.0 :mut 4.5 :dim 4.5 :amber 4.5 :slate 4.5 :cad 4.5 :bad 4.5 :cyan 4.5})

(defn- lin
  "sRGB channel 0..255 → linear light."
  [c]
  (let [x (/ (long c) 255.0)]
    (if (<= x 0.04045) (/ x 12.92) (math/pow (/ (+ x 0.055) 1.055) 2.4))))

(m/=> lin [:=> [:cat :int] :double])

(defn luminance
  "WCAG relative luminance of a `#rrggbb` colour, 0 (black) .. 1 (white)."
  [hex]
  (let [ch (fn [i] (Long/parseLong (subs hex i (+ i 2)) 16))]
    (+ (* 0.2126 (lin (ch 1))) (* 0.7152 (lin (ch 3))) (* 0.0722 (lin (ch 5))))))

(m/=> luminance [:=> [:cat hex-colour] :double])

(defn contrast
  "WCAG contrast ratio (1..21) between two #rrggbb colours

   WCAG contrast ratio between two `#rrggbb` colours, 1.0 .. 21.0 — the
   number the palette floors are checked against; order does not matter."
  [a b]
  (let [la (luminance a) lb (luminance b)]
    (/ (+ (max la lb) 0.05) (+ (min la lb) 0.05))))

(m/=> contrast [:=> [:cat hex-colour hex-colour] [:double {:min 1.0 :max 21.0}]])

(def contrast-failure
  "One palette pair under its floor."
  [:map [:fg :keyword] [:bg :keyword] [:ratio :double] [:floor :double]])

(defn palette-failures
  "Palette role/ground pairs under their WCAG contrast floor (empty = ok)

   Every (foreground role, ground) pair of `palette` that is under its
   `contrast-floors` floor on `:bg`, `:panel` and `:panel2` — empty means the
   palette passes. Pure; `build!` refuses a deck whose palette fails."
  [palette]
  (vec (for [ground [:bg :panel :panel2]
             [fg floor] contrast-floors
             :let [r (contrast (get palette fg) (get palette ground))]
             :when (< r floor)]
         {:fg fg :bg ground :ratio r :floor floor})))

(m/=> palette-failures [:=> [:cat palette-spec] [:vector contrast-failure]])

;; ------------------------------------------------------------------ specs

(def ^:private ne-string [:string {:min 1}])

(def slide-spec
  "One slide: an optional kicker, title and lead above a hiccup `:body`;
   `:class \"title\"` renders the body alone (the cover slide); `:scroll? true`
   opts a slide into vertical scrolling (the appendix shape) so the shooter
   does not count its overflow as a defect."
  [:map
   [:kicker {:optional true} ne-string]
   [:title {:optional true} ne-string]
   [:lead {:optional true} ne-string]
   [:class {:optional true} [:enum "title"]]
   [:scroll? {:optional true} :boolean]
   [:body [:vector {:min 1} some?]]])

(def deck-spec
  "A deck: document title, the directory asset paths resolve against, the
   palette (default `dark-palette`), whether to inline Inter, and the slides."
  [:map
   [:title ne-string]
   [:root ne-string]
   [:lang {:optional true} ne-string]
   [:palette {:optional true} palette-spec]
   [:font {:optional true} [:enum :inter :system]]
   [:allow-h264? {:optional true} :boolean]
   [:slides [:vector {:min 1} slide-spec]]])

;; ----------------------------------------------------------------- assets

(def ^:private mime-by-ext
  {"png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg" "webp" "image/webp"
   "gif" "image/gif" "svg" "image/svg+xml" "avif" "image/avif"
   "mp4" "video/mp4" "webm" "video/webm" "mov" "video/quicktime"
   "woff2" "font/woff2" "woff" "font/woff" "json" "application/json"})

(defn- ext-of
  "Lower-case extension of `path`, or \"\"."
  [path]
  (if-let [i (str/last-index-of path ".")]
    (str/lower-case (subs path (inc i)))
    ""))

(m/=> ext-of [:=> [:cat [:string {:min 0}]] [:string {:min 0}]])

(defn data-uri
  "`data` (a byte array) as a base64 `data:` URI with `mime`."
  [mime ^bytes data]
  (str "data:" mime ";base64," (.encodeToString (Base64/getEncoder) data)))

(m/=> data-uri [:=> [:cat ne-string bytes?] ne-string])

(defn- resolve-asset
  "The file for asset `path` under `root` (absolute paths stand). Throws
   `:deck/missing-asset` when it does not exist — a self-contained deck has
   no path a reader could repair."
  ^File [root path]
  (let [f (let [f (io/file path)] (if (.isAbsolute f) f (io/file root path)))]
    (when-not (.isFile f)
      (throw (ex-info (str "deck asset missing: " path)
                      {:deck/refusal :deck/missing-asset :path path :root root})))
    f))

(m/=> resolve-asset [:=> [:cat ne-string ne-string] [:fn (fn [x] (instance? File x))]])

(def asset-use
  "One inlined asset in the build report."
  [:map [:path ne-string] [:mime ne-string] [:bytes :int] [:uses :int]])

(defn- h264?
  "True for containers whose codec the offline checker cannot decode."
  [path]
  (contains? #{"mp4" "mov"} (ext-of path)))

(m/=> h264? [:=> [:cat ne-string] :boolean])

;; --------------------------------------------------------------- expansion

(defn- figure
  "A `<figure>` with an optional caption."
  [media caption]
  (into [:figure.fig media] (when caption [[:figcaption caption]])))

(m/=> figure [:=> [:cat vector? [:maybe [:string {:min 0}]]] vector?])

(defn- media-attrs
  "`src` for the first use of an asset, `data-from` for every later one (the
   page script re-points the copies at build time, so the bytes exist once)."
  [assets! path uri]
  (let [id (str "as" (count @assets!))
        seen (get @assets! path)]
    (if seen
      (do (vswap! assets! update-in [path :uses] inc)
          {:data-from (:id seen)})
      (do (vswap! assets! assoc path {:id id :uses 1})
          {:id id :src uri}))))

(m/=> media-attrs [:=> [:cat [:fn volatile?] ne-string ne-string] [:map-of :keyword [:string {:min 0}]]])

(defn- inline-asset
  "Read asset `path` under the deck's root, record its bytes, and return the
   media attributes (`src` on first use, `data-from` after)."
  [deck assets! bytes! path]
  (let [f (resolve-asset (:root deck) path)
        data (Files/readAllBytes (.toPath f))
        mime (get mime-by-ext (ext-of path) "application/octet-stream")]
    (vswap! bytes! assoc path {:mime mime :bytes (alength ^bytes data)})
    (media-attrs assets! path (data-uri mime data))))

(m/=> inline-asset [:=> [:cat deck-spec [:fn volatile?] [:fn volatile?] ne-string] [:map-of :keyword [:string {:min 0}]]])

(defn- expand-img
  "`[:deck/img path caption?]` → a figure with the inlined image."
  [deck assets! bytes! [_ path caption]]
  (figure [:img (assoc (inline-asset deck assets! bytes! path) :alt (or caption path) :loading "lazy")]
          caption))

(m/=> expand-img [:=> [:cat deck-spec [:fn volatile?] [:fn volatile?] vector?] vector?])

(defn- expand-vid
  "`[:deck/vid path caption?]` → a looping muted figure; H.264 containers are
   refused unless the deck opts in, because the offline checker cannot
   decode them."
  [deck assets! bytes! [_ path caption]]
  (when (and (h264? path) (not (:allow-h264? deck)))
    (throw (ex-info (str "deck video is H.264 (" path "): the offline checker's Chromium cannot decode it — encode AV1/VP9, or set :allow-h264? true")
                    {:deck/refusal :deck/h264 :path path})))
  (figure [:video (assoc (inline-asset deck assets! bytes! path)
                         :autoplay true :muted true :loop true :playsinline true :preload "metadata")]
          caption))

(m/=> expand-vid [:=> [:cat deck-spec [:fn volatile?] [:fn volatile?] vector?] vector?])

(defn- expand-layout
  "The asset-free forms: card, grid, table, list, raw markup — or nil when
   `tag` is not one of them."
  [[tag a b c :as form]]
  (case (name tag)
    "card" [:div {:class (str "card" (when c (str " " c)))} (when a [:h3 a]) b]
    "grid" (into [:div.grid {:style (str "grid-template-columns:" a)}] (nnext form))
    "tbl" [:div.tblwrap
           [:table.tbl
            [:thead (into [:tr] (map (fn [x] [:th x])) a)]
            (into [:tbody] (map (fn [row] (into [:tr] (map (fn [x] [:td x])) row))) b)]]
    "ul" (into [:ul] (map (fn [x] [:li x])) (next form))
    "raw" (h/raw a)
    nil))

(m/=> expand-layout [:=> [:cat vector?] [:maybe some?]])

(defn- deck-form?
  "A `:deck/*`-tagged vector."
  [form]
  (and (vector? form) (keyword? (first form)) (= "deck" (namespace (first form)))))

(m/=> deck-form? [:=> [:cat some?] :boolean])

(defn- expand-form
  "One asset/layout form → plain hiccup; anything else passes through.
   Refuses an unknown `:deck/*` tag by name."
  [deck assets! bytes! form]
  (if-not (deck-form? form)
    form
    (let [tag (name (first form))]
      (case tag
        "img" (expand-img deck assets! bytes! form)
        "vid" (expand-vid deck assets! bytes! form)
        (or (expand-layout form)
            (throw (ex-info (str "unknown deck form " (first form))
                            {:deck/refusal :deck/unknown-form :form (first form)})))))))

(m/=> expand-form [:=> [:cat deck-spec [:fn volatile?] [:fn volatile?] some?] some?])

(defn- expand
  "Walk a slide body, expanding every `:deck/*` form. `prewalk`, so a card's
   body that itself holds an image is expanded after the card is."
  [deck assets! bytes! body]
  (walk/prewalk (partial expand-form deck assets! bytes!) body))

(m/=> expand [:=> [:cat deck-spec [:fn volatile?] [:fn volatile?] vector?] vector?])

;; ------------------------------------------------------------------ theme

(defn- resource-text
  "A theme resource as text; a missing one is a packaging fault, thrown."
  [nm]
  (if-let [r (io/resource (str "press/deck/" nm))]
    (slurp r)
    (throw (ex-info (str "deck theme resource missing: " nm) {:deck/refusal :deck/no-theme :name nm}))))

(m/=> resource-text [:=> [:cat ne-string] ne-string])

(defn theme-css
  "The deck CSS with `palette` filled into its `{{key}}` placeholders."
  [palette]
  (reduce-kv (fn [css k v] (str/replace css (str "{{" (name k) "}}") v))
             (resource-text "deck.css") palette))

(m/=> theme-css [:=> [:cat palette-spec] ne-string])

(defn font-css
  "Inter (variable, SIL OFL 1.1) as an inline `@font-face`, or \"\" for the
   system stack."
  [font]
  (if (= font :system)
    ""
    (let [r (or (io/resource "press/deck/Inter.var.woff2")
                (throw (ex-info "Inter.var.woff2 resource missing" {:deck/refusal :deck/no-theme})))
          data (with-open [in (io/input-stream r)] (.readAllBytes in))]
      (str "@font-face{font-family:\"Inter\";font-style:normal;font-weight:100 900;"
           "font-display:swap;src:url(" (data-uri "font/woff2" data) ") format(\"woff2\")}"))))

(m/=> font-css [:=> [:cat [:enum :inter :system]] [:string {:min 0}]])

;; ----------------------------------------------------------------- render

(defn- slide-html
  "One `<section class=\"slide\">`; the first slide carries `on`."
  [deck assets! bytes! first? {:keys [kicker title lead scroll? body] kind :class}]
  (let [cls (str "slide" (when first? " on") (when scroll? " scroll"))
        body' (expand deck assets! bytes! body)]
    (if (= kind "title")
      (into [:section {:class cls}] body')
      [:section {:class cls}
       (when kicker [:p.kicker kicker])
       (when title [:h2 title])
       (when lead [:p.lead lead])
       (into [:div.body] body')])))

(m/=> slide-html [:=> [:cat deck-spec [:fn volatile?] [:fn volatile?] :boolean slide-spec] vector?])

(def render-result
  "What `render*` returns: the page and the asset table behind it."
  [:map [:html ne-string] [:assets [:vector asset-use]]])

(defn- render*
  "Render `deck`; returns the HTML and the inlined-asset table."
  [deck]
  (when-not (m/validate deck-spec deck)
    (throw (ex-info "malformed deck" {:deck/refusal :deck/malformed
                                      :explain (m/explain deck-spec deck)})))
  (let [palette (or (:palette deck) dark-palette)
        assets! (volatile! {})
        bytes! (volatile! {})
        sections (map-indexed (fn [i s] (slide-html deck assets! bytes! (zero? i) s)) (:slides deck))
        html (str "<!DOCTYPE html>\n"
                  (h/html {:mode :html}
                          [:html {:lang (or (:lang deck) "en")}
                           [:head
                            [:meta {:charset "UTF-8"}]
                            [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
                            [:title (:title deck)]
                            [:style (h/raw (font-css (or (:font deck) :inter)))]
                            [:style (h/raw (theme-css palette))]]
                           [:body
                            (into [:div.deck {:id "deck"}] sections)
                            [:button.pdf {:onclick "window.print()"} "Save as PDF"]
                            [:div.arrows
                             [:button.abtn {:onclick "go(-1)" :aria-label "Previous slide"} (h/raw "&larr;")]
                             [:button.abtn {:onclick "go(1)" :aria-label "Next slide"} (h/raw "&rarr;")]]
                            [:div.nav {:id "nav"}]
                            [:div.pageno {:id "pageno"}]
                            [:script (h/raw (resource-text "deck.js"))]]]))]
    {:html html
     :assets (vec (for [[path {:keys [uses]}] @assets!
                        :let [{:keys [mime] n :bytes} (get @bytes! path)]]
                    {:path path :mime mime :bytes n :uses uses}))}))

(m/=> render* [:=> [:cat deck-spec] render-result])

(defn render
  "Render a deck spec to ONE self-contained HTML string; writes nothing

   `deck` (see `deck-spec`) → ONE HTML string with the theme, the font and
   every asset inlined. Throws `:deck/malformed`, `:deck/missing-asset`,
   `:deck/h264`, `:deck/unknown-form`. Does not check contrast or external
   references — `build!` does, and refuses; use `check` on the result to see
   what it would refuse."
  [deck]
  (:html (render* deck)))

(m/=> render [:=> [:cat deck-spec] ne-string])

;; ------------------------------------------------------------------ check

(def ^:private external-attr
  #"(?i)\b(?:src|href|poster|srcset|data-src)\s*=\s*[\"']([^\"']+)[\"']")

(def ^:private external-url-fn
  #"(?i)url\(\s*[\"']?([^\"')]+)[\"']?\s*\)")

(def ^:private network-code
  #"(?i)\b(?:fetch\s*\(|XMLHttpRequest|new\s+Worker\s*\(|import\s*\(|navigator\.sendBeacon|new\s+EventSource\s*\(|new\s+WebSocket\s*\()")

(defn- internal-ref?
  "A reference that stays inside the file."
  [reference]
  (let [r (str/trim reference)]
    (or (str/starts-with? r "data:") (str/starts-with? r "#")
        ;; `#` URL-encoded — an anchor inside an inlined SVG data URI (a
        ;; gradient reference such as DaisyUI's `url(%23a)`), not a file
        (str/starts-with? r "%23")
        (str/starts-with? r "about:blank") (str/starts-with? r "javascript:"))))

(m/=> internal-ref? [:=> [:cat [:string {:min 0}]] :boolean])

(def ^:private script-body
  #"(?is)<script\b[^>]*>(.*?)</script>")

(defn- path-like?
  "Does a quoted value look like a path or a URL — a scheme, a slash, or a
   file extension? Inside a `<script>` body the attribute and `url()` scans
   also hit identifiers and short literals (`resolveURL(a)`, `getDataURL(a,b)`,
   `x.src=\"a\"` in a minified bundle), so only path-shaped hits count there."
  [s]
  (boolean (re-find #"(?i)^(?:[a-z][a-z0-9+.-]*:|//)|/|\.[a-z0-9]{2,5}(?:[?#]|$)" (str/trim s))))

(m/=> path-like? [:=> [:cat [:string {:min 0}]] :boolean])

(defn- refs-in
  "Every attribute and CSS `url()` reference in `text` that is not internal."
  [text]
  (remove internal-ref? (concat (map second (re-seq external-attr text))
                                (map second (re-seq external-url-fn text)))))

(m/=> refs-in [:=> [:cat [:string {:min 0}]] [:sequential [:string {:min 0}]]])

(def check-result
  "The offline verdict over an HTML string."
  [:map
   [:external [:vector ne-string]]
   [:imports [:vector ne-string]]
   [:network-code [:vector ne-string]]
   [:data-uris :int]
   [:bytes :int]
   [:self-contained? :boolean]])

(defn check
  "Offline gate over an HTML string: external refs, network code, bytes

   The offline gate over `html`: `:external` is every `src`/`href`/`poster`/
   `srcset`/`data-src` value and every CSS `url()` that is not a `data:` URI
   or an in-page anchor — unconditionally in markup and style, and only when
   path-shaped inside `<script>` bodies (see `path-like?`); `:imports` every
   `@import`; `:network-code` every inline-script token that could reach the
   network (`fetch(`, `XMLHttpRequest`, `import(`, workers, sockets —
   reported, and refused by `build!`); `:data-uris` how many assets are
   inlined; `:self-contained?` when all three lists are empty. Pure; works on
   any HTML, not only ours."
  [html]
  (let [scripts (map second (re-seq script-body html))
        markup (str/replace html script-body "<script></script>")
        external (vec (distinct (concat (refs-in markup)
                                        (filter path-like? (mapcat refs-in scripts)))))
        imports (vec (re-seq #"(?i)@import\s+[^;]+" html))
        net (vec (distinct (re-seq network-code html)))]
    {:external external
     :imports imports
     :network-code net
     :data-uris (count (re-seq #"data:[a-z]+/[a-z0-9.+-]+;base64," html))
     :bytes (alength (.getBytes ^String html "UTF-8"))
     :self-contained? (and (empty? external) (empty? imports) (empty? net))}))

(m/=> check [:=> [:cat [:string {:min 0}]] check-result])

;; ----------------------------------------------------------------- build!

(def build-report
  "What `build!` returns for a deck it wrote."
  [:map
   [:path ne-string]
   [:bytes :int]
   [:slides :int]
   [:assets [:vector asset-use]]
   [:check check-result]
   [:palette-failures [:vector contrast-failure]]])

(defn- write-atomically!
  "Write `html` to `path` via a sibling temp file and an atomic move, so a
   half-written deck never exists at the destination."
  [^String path ^String html]
  (let [dest (io/file path)
        tmp (File/createTempFile "deck-" ".html" (or (.getParentFile (.getAbsoluteFile dest)) (io/file ".")))]
    (spit tmp html)
    (Files/move (.toPath tmp) (.toPath dest)
                (into-array [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
    (.length dest)))

(m/=> write-atomically! [:=> [:cat ne-string ne-string] :int])

(defn build!
  "Write ONE self-contained deck HTML file, or refuse and write nothing

   Render `deck`, run `check` and the palette contrast floors, and write ONE
   self-contained file at `out-path` — or refuse with `:deck/refused` (carrying
   the check result and the failing pairs) and write NOTHING. A deck that
   would need something beside it cannot come out of this function."
  [deck out-path]
  (let [{:keys [html assets]} (render* deck)
        verdict (check html)
        failures (palette-failures (or (:palette deck) dark-palette))]
    (when (or (not (:self-contained? verdict)) (seq failures))
      (throw (ex-info (str "deck refused: " (count (:external verdict)) " external reference(s), "
                           (count (:imports verdict)) " @import(s), "
                           (count (:network-code verdict)) " network token(s), "
                           (count failures) " contrast failure(s)")
                      {:deck/refusal :deck/refused :check verdict :palette-failures failures})))
    {:path out-path
     :bytes (write-atomically! out-path html)
     :slides (count (:slides deck))
     :assets assets
     :check verdict
     :palette-failures failures}))

(m/=> build! [:=> [:cat deck-spec ne-string] build-report])

(defn example
  "Smallest valid two-slide deck spec, for smoke tests and the docs

   A two-slide deck over `root` (a directory holding `img` and, optionally,
   `vid`) — the smallest valid input, for smoke tests and the docs."
  [root img]
  {:title "deck smoke"
   :root root
   :slides [{:class "title"
             :body [[:div.fill.grow [:p.kicker "smoke"] [:h1 "A deck"] [:p.lead "One file."]]]}
            {:kicker "one" :title "An image, twice"
             :lead "The second use is re-pointed at the first."
             :body [[:deck/grid "1fr 1fr" [:deck/img img "left"] [:deck/img img "right"]]
                    [:deck/card "Table" [:deck/tbl ["k" "v"] [["a" "1"] ["b" "2"]]]]]}]})

(m/=> example [:=> [:cat ne-string ne-string] deck-spec])

;; -------------------------------------------------------------------- qa

(def qa-opts
  "Options for `qa`: `:only` the slide numbers to shoot (default all);
   `:scroll-slides` slides allowed to scroll (their overflow demotes to info);
   `:strict?` fails on slide or deck errors; warnings remain in the report. `:font-family` the family
   D6 expects to see applied (default Inter)."
  [:map {:closed true}
   [:only {:optional true} [:vector [:int {:min 1}]]]
   [:scroll-slides {:optional true} [:vector [:int {:min 1}]]]
   [:strict? {:optional true} :boolean]
   [:font-family {:optional true} ne-string]])

(def qa-command
  "What `qa` returns: the HOST command that shoots and checks the deck, and
   where its report will land."
  [:map {:closed true}
   [:html ne-string] [:out ne-string] [:report ne-string] [:argv [:vector ne-string]]])

(defn qa
  "Build the host command that shoots and QA-checks a deck (runs nothing)

   The shooter and the twelve visual-defect detectors for the deck at
   `html-path`, as the HOST command that runs them (the configured launcher
   — `press.config`, by default `tools/press/bin/press` — with `qa`, run
   from the configured root once the tool image is built). It writes one
   PNG per slide, a numbered contact sheet, `report.json` and `report.md`
   into `out-dir`; `qa-report` reads the verdict back and the PNGs are what
   a visual review reads. Both paths are passed through AS GIVEN: they are
   HOST paths as the shell that runs the command sees them, and this
   library starts no container, so the command is the deliverable. `opts`
   is a `qa-opts`. What it catches that `check` cannot: overflow, glyph-level text
   overlap, truncation, occlusion, small text, font fallback, broken or
   upscaled images, undecodable video, contrast over the rendered background,
   runtime and offline network reach, console errors."
  ([html-path out-dir] (qa html-path out-dir {}))
  ([html-path out-dir {:keys [only scroll-slides strict? font-family]}]
   (let [csv #(str/join "," %)
         argv (cond-> [(config/launcher) "qa" html-path out-dir]
                (seq only) (into ["--only" (csv only)])
                (seq scroll-slides) (into ["--scroll-slides" (csv scroll-slides)])
                font-family (into ["--font-family" font-family])
                strict? (conj "--strict"))]
     {:html html-path
      :out out-dir
      :report (str (io/file out-dir "report.json"))
      :argv argv})))

(m/=> qa [:function
          [:=> [:cat ne-string ne-string] qa-command]
          [:=> [:cat ne-string ne-string qa-opts] qa-command]])

(def qa-finding
  "One error-severity finding: the slide it is on (nil for a deck-level one),
   the detector, and the detector's own sentence."
  [:map {:closed true}
   [:slide [:maybe :int]] [:detector ne-string] [:message ne-string]])

(def qa-verdict
  "`qa-report`'s answer. READ `:ok?` FIRST — true only when no slide and no
   deck-level finding is an error. `:errors` carries every error with its
   slide and message so the fix site is named without opening the JSON;
   `:by-detector` counts every finding by `detector/severity`."
  [:map {:closed true}
   [:report ne-string]
   [:ok? :boolean]
   [:slides :int]
   [:slide-errors :int] [:slide-warnings :int]
   [:deck-errors :int] [:deck-warnings :int]
   [:error-slides [:vector :int]] [:warn-slides [:vector :int]]
   [:by-detector [:map-of ne-string :int]]
   [:errors [:vector qa-finding]]])

(defn- error-findings
  "The error-severity findings of `findings`, tagged with `slide`."
  [slide findings]
  (for [x findings :when (= "error" (get x "severity"))]
    {:slide slide :detector (get x "detector") :message (get x "message")}))

(m/=> error-findings [:=> [:cat [:maybe :int] [:maybe [:sequential [:map-of :string :any]]]]
                      [:sequential qa-finding]])

(defn qa-report
  "Read the report.json a deck QA run wrote, as a verdict map

   Read the `report.json` the `qa` command wrote into `out-dir` as a
   `qa-verdict`. Refuses `:deck/no-report` when the file is not there — the
   command has not run, or ran elsewhere; nothing is inferred from a missing
   report."
  [out-dir]
  (let [f (io/file out-dir "report.json")]
    (when-not (.isFile f)
      (throw (ex-info (str "no report.json in " out-dir " — run the `qa` command first")
                      {:deck/refusal :deck/no-report :path (str f)})))
    (let [r (json/parse-string (slurp f))
          t (get r "totals")
          errors (vec (concat (mapcat #(error-findings (get % "n") (get % "findings")) (get r "slides"))
                              (error-findings nil (get r "deck_findings"))))]
      {:report (str f)
       :ok? (empty? errors)
       :slides (int (get t "slides" 0))
       :slide-errors (int (get t "slide_errors" 0))
       :slide-warnings (int (get t "slide_warnings" 0))
       :deck-errors (int (get t "deck_errors" 0))
       :deck-warnings (int (get t "deck_warnings" 0))
       :error-slides (vec (get t "error_slides"))
       :warn-slides (vec (get t "warn_slides"))
       :by-detector (into {} (get t "by_detector"))
       :errors errors})))

(m/=> qa-report [:=> [:cat ne-string] qa-verdict])
