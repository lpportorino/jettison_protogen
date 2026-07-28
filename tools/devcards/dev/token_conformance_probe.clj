(ns token-conformance-probe
  "Empirical probe: is every colour the renderer DRAWS a value the token
   manifest declares?

   This is the arming measurement for the RECOLOR arm that
   `docs/UI-QUALITY-CONTRACTS.md` §6.7 hands to the static tier — AND IT
   REFUTED THAT SECTION'S FIRST PREMISE RATHER THAN SIZING IT. The argument
   §6.7 shipped with was: with the opacity ban in force the AUTHORED pair IS
   the RENDERED pair, so every colour the dump reports must be a token value,
   and a whole-widget recolor produces a composite that appears in no token
   table — detectable by exactly this comparison.

   THE PREMISE IS FALSE AS WRITTEN, and this probe's own numbers are what
   showed it. The ban is on OPACITY. A RECOLOR composes a new value over
   whatever colour it lands on, and it is untouched by that ban: 1206 of 3082
   drawn colours here are in no token table, and 91 of those come from this
   theme's OWN recolor styles — 26 from `disabled_dim`, the mechanism §6
   PRESCRIBES for text-free geometry. An arm armed on the unscoped reading
   would report §6's own design as a violation of §6. §6.7 now carries the
   retraction; §6.2 carries the scope in which the identity does hold (a
   text-bearing widget under `disabled`, where recolor_opa is pinned TRANSP).

   So the comparison below is still worth running and is NOT a gate: read it as
   a census of drawn-vs-declared colour, whose job is to separate this theme's
   DECLARED recolors from everything else before any clause blocks on it.

   IT DOES NOT DETECT RECOLORS. It detects a drawn colour that is in no token
   table, which is a FACT. A recolor is one cause; a stock LVGL colour leaking
   through the theme patch is another (`theme.c` inherits `color_primary` and
   `color_secondary` from stock), and a deliberately non-token colour is a
   third. Naming the finding after one cause would over-claim, and the clause
   this probe is sizing must not either.

   AN OBSERVATION IS NOT A DEFECT, AND FOR `text_color` IT IS NOT EVEN A STYLE.
   Read the counts as EMISSIONS, never as a population of things to fix — three
   separate reasons, each measured on this corpus:
     - `text_color` is emitted only where it CHANGES, so the SCREEN ROOT
       contributes exactly one emission per card x mode and they all come from
       ONE style object. That alone was 492 of the 790 non-token `text_color`
       emissions. The root/non-root split below exists so that share is never
       again read as a count of bad styles.
     - A handful of styles can generate many distinct hexes, because a
       `recolor` composes with whatever colour was inherited, so ONE style
       yields a different composite over every base it lands on. Most of the
       long tail of distinct hexes here is that, and much of it is THIS
       THEME'S OWN — its hover, pressed and disabled_dim styles all carry a
       recolor, and the last of those is the mechanism §6 PRESCRIBES for
       text-free geometry. So the tail is not a leak inventory, and the
       distinct-(class,hex) line is a closer proxy for \"how many decisions\"
       than the raw total.
     - `text_color` resolves on EVERY object, including ones that put no glyph
       on screen (switch, slider, bar, arc, chart). Those emissions colour
       nothing. This probe cannot tell them apart: `dump_obj` carries no
       draws-text flag, and reconstructing one here would mean copying
       `obj_draws_text`'s class list out of the renderer — the drift-prone
       enumeration this repo bans. Treat the totals as an upper bound.

   WHICH KEYS, AND WHICH WAY EACH ABSENCE FAILS — from `dump_obj`'s own
   vocabulary block in `renderer/src/main.c`, which is its one home:
     text_color     absent => inherited from the nearest ancestor that emitted
                    one. Nothing new is drawn here, so there is nothing to
                    check — this is one of the rare keys whose absence really
                    is neutral FOR THIS QUESTION.
     bg_color       absent => this node paints NO MAIN FILL. Again nothing
                    drawn, nothing to check. It does NOT mean 'the default
                    background'.
     text_on.color  the text colour for a class that draws its text on another
     text_on.bg     PART (buttonmatrix, table, roller, scale). Emitted only for
                    those, and MAIN's keys do not describe them.
   Every one of those passes through `style_color_drawn`, i.e. the colour as
   DRAWN rather than as authored — which is the whole point, since that is
   where a recolor composite would surface.

   BOTH THEMES, because a token declares a `dark` and a `light` value and the
   theme resolves whichever the mode selects; judging one mode would judge half
   the palette against the wrong half of the table.

   ONLY THE ASGARD FAMILY IS RENDERED, matching what the DOM lane actually
   judges (`core.clj` runs the invariant lanes over family 0). The vanilla and
   stock arms draw stock colours by construction and would report a large,
   uninteresting non-conformance that says nothing about our theme.

   Read-only: renders, counts, prints. Writes nothing, gates nothing, and is
   not part of the battery.

   Run (in the toolchain container, from tools/devcards/):
     clojure -M:bindings:token-conformance"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [devcards.composition :as composition]
            [devcards.fixtures :as fixtures]
            [devcards.host :as host]))

(set! *warn-on-reflection* true)

(def ^:private canvas {:w 800 :h 480})
(def ^:private tokens-path "../../output/manifests/design-tokens.json")

(defn- render+dump!
  [^bytes pb dark]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w (:w canvas)
                        :h (:h canvas)})]
    (try (host/render-card! h {:pb pb :bp 0 :dark (if dark 1 0)})
         (json/read-str (host/dump-tree! h) :key-fn keyword)
         (finally (host/close! h)))))

(defn- walk
  "Every node, tagged with whether it is the screen root — the one node
   `dump_obj` guarantees emits `text_color`, because it has no parent to
   differ from."
  [root]
  (cons (assoc root :root? true)
        (rest (tree-seq #(seq (:children %)) :children root))))

(defn- token-table
  "mode -> #{hex}. Colour tokens only; a border-width or a radius has no hex."
  []
  (let [tokens (:tokens (json/read-str (slurp tokens-path) :key-fn keyword))
        colours (filter (fn [[_ v]] (= "color" (:kind v))) tokens)]
    {:dark (set (map (comp str/upper-case :dark val) colours))
     :light (set (map (comp str/upper-case :light val) colours))
     :by-hex (reduce (fn [m [k v]]
                       (-> m
                           (update (str/upper-case (:dark v)) (fnil conj #{}) (name k))
                           (update (str/upper-case (:light v)) (fnil conj #{}) (name k))))
                     {} colours)}))

(defn- drawn-colours
  "Every colour this node DRAWS, as {:key :hex}. Absent keys contribute
   nothing — see the ns docstring for why that is sound for THIS question and
   is not a general licence to read absence as neutral."
  [node]
  (let [t (:text_on node)]
    (cond-> []
      (:text_color node) (conj {:key :text_color :hex (:text_color node)})
      (:bg_color node) (conj {:key :bg_color :hex (:bg_color node)})
      (:color t) (conj {:key :text_on.color :hex (:color t)})
      (:bg t) (conj {:key :text_on.bg :hex (:bg t)}))))

(defn -main
  [& _]
  (let [{:keys [dark light by-hex]} (token-table)
        spec (fixtures/load-spec)
        built (concat (fixtures/build-all spec)
                      (composition/build-all (composition/load-inventory)))
        _ (println (format "%d colour tokens: %d distinct dark hexes, %d light"
                           (count by-hex) (count dark) (count light)))
        _ (println (format "rendering %d cards x 2 themes…" (count built)))
        obs (into []
                  (for [{:keys [id] ^bytes pb :bytes} built
                        mode [:dark :light]
                        :let [tree (render+dump! pb (= mode :dark))]
                        node (walk tree)
                        c (drawn-colours node)]
                    (assoc c :card (str id "|" (name mode))
                           :mode mode
                           :class (:type node)
                           :root? (boolean (:root? node))
                           :hex (str/upper-case (:hex c)))))
        valid {:dark dark :light light}
        {ok true bad false} (group-by #(contains? (valid (:mode %)) (:hex %)) obs)]

    (println (format "\n%d drawn colour(s) observed; %d IN the token table, %d NOT"
                     (count obs) (count ok) (count bad)))

    (println "\n══ BY KEY ══")
    (doseq [[k es] (sort-by key (group-by :key obs))]
      (let [n-bad (count (remove #(contains? (valid (:mode %)) (:hex %)) es))]
        (println (format "  %-16s %5d observed  %5d non-token" (str k) (count es) n-bad))))

    ;; See the ns docstring: emissions are not defects, and for text_color the
    ;; root's share is one style object restated once per render.
    (println "\n══ text_color: ROOT vs NON-ROOT ══")
    (let [t (filter #(= :text_color (:key %)) bad)
          {r true nr false} (group-by :root? t)]
      (println (format "  screen root      %5d  (one style object, once per card x mode)"
                       (count r)))
      (println (format "  everything else  %5d  in %d distinct (class,hex) pair(s), %d class(es)"
                       (count nr)
                       (count (distinct (map (juxt :class :hex) nr)))
                       (count (distinct (map :class nr))))))

    (if (empty? bad)
      (println "\n══ CLEAN ══ every drawn colour is a declared token value.")
      (do
        (println "\n══ NON-TOKEN COLOURS ══ (hex, how often, which classes, sample card)")
        (doseq [[hex es] (sort-by (comp - count val) (group-by :hex bad))]
          (println (format "  %-9s x%-5d %-34s %s"
                           hex (count es)
                           (pr-str (vec (sort (distinct (map :class es)))))
                           (:card (first es))))
          (println (format "            keys=%s modes=%s"
                           (pr-str (vec (sort (distinct (map (comp str :key) es)))))
                           (pr-str (vec (sort (distinct (map (comp name :mode) es))))))))))

    (println "\n══ CONTROL: is the comparison discriminating? ══")
    (println (format "  distinct hexes drawn: %d; distinct token hexes: %d"
                     (count (distinct (map :hex obs)))
                     (count (into dark light))))
    (println (format "  a token hex NEVER drawn: %s"
                     (pr-str (vec (sort (remove (set (map :hex obs)) (into dark light)))))))
    (println "  (a run where every drawn hex matched because the table contained")
    (println "   every possible hex would show 0 unused tokens — it does not.)")
    (println "  A NEVER-DRAWN TOKEN IS NOT BY ITSELF A FINDING, and on this corpus")
    (println "  not one of them was: check both structural reasons before chasing one.")
    (println "  (1) The manifest declares more colour tokens than the theme can")
    (println "      reach. lvgl-codegen.theme-tokens' `fields` table is the closed")
    (println "      token->C projection and its one home; a token absent from it has")
    (println "      no THEME_* define, so theme.c CANNOT draw it — the token is")
    (println "      manifest-and-docs only.")
    (println "  (2) This probe reads four colour keys. LVGL also draws border,")
    (println "      outline, arc, line and shadow colours, and `dump_obj` emits none")
    (println "      of them — so a token used ONLY for one of those (the FOCUS_KEY")
    (println "      outline is exactly this case) is drawn on every focus card and")
    (println "      still lands in this list.")))
