(ns token-conformance-probe
  "Empirical probe: is every colour the renderer DRAWS a value the token
   manifest declares?

   This is the arming measurement for the RECOLOR arm that
   `docs/UI-QUALITY-CONTRACTS.md` §6.7 hands to the static tier. That section's
   argument is short and worth restating, because the probe is meaningless
   without it: with the opacity ban in force the AUTHORED pair IS the RENDERED
   pair, so every colour the dump reports must be a token value — and a
   whole-widget recolor produces a composite that appears in no token table,
   which makes it detectable by exactly this comparison. The comparison is only
   valid BECAUSE the ban holds; without it a faded colour would be a legitimate
   non-token value and the arm would drown.

   IT DOES NOT DETECT RECOLORS. It detects a drawn colour that is in no token
   table, which is a FACT. A recolor is one cause; a stock LVGL colour leaking
   through the theme patch is another (`theme.c` inherits `color_primary` and
   `color_secondary` from stock), and a deliberately non-token colour is a
   third. Naming the finding after one cause would over-claim, and the clause
   this probe is sizing must not either.

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

(defn- walk [root] (tree-seq #(seq (:children %)) :children root))

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
                           :hex (str/upper-case (:hex c)))))
        valid {:dark dark :light light}
        {ok true bad false} (group-by #(contains? (valid (:mode %)) (:hex %)) obs)]

    (println (format "\n%d drawn colour(s) observed; %d IN the token table, %d NOT"
                     (count obs) (count ok) (count bad)))

    (println "\n══ BY KEY ══")
    (doseq [[k es] (sort-by key (group-by :key obs))]
      (let [n-bad (count (remove #(contains? (valid (:mode %)) (:hex %)) es))]
        (println (format "  %-16s %5d observed  %5d non-token" (str k) (count es) n-bad))))

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
    (println "   every possible hex would show 0 unused tokens — it does not.)")))
