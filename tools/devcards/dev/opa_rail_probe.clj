(ns opa-rail-probe
  "Empirical probe: does the dump's `opa` key reproduce LVGL's OWN opacity
   accumulation, INCLUDING ITS CLAMPS?

   Opacity in LVGL does not compose as a product, and every deviation is at a
   rail, so a float-product implementation agrees everywhere except exactly
   where it matters:

     - a link >= LV_OPA_MAX (253) is SKIPPED from the multiply entirely
       (`if(opa_main < LV_OPA_MAX)` in lv_refr.c refr_obj), so 255 is neutral
       and 253 is neutral TOO;
     - a link <= LV_OPA_MIN (2) collapses the whole chain to transparent
       (lv_obj_style.c lv_obj_get_style_opa_recursive short-circuits; the draw
       path reaches the same place by rounding MIX2 to zero);
     - the multiply is LV_OPA_MIX2 — integer `(a*b)>>8`, not `a*b/255` — so it
       loses a little at every step and the losses are not associative with a
       float model.

   Reading the C is not evidence that it matches. This probe builds screens
   that sit ON those rails, renders them, and cross-checks the dumped number
   against LVGL's own answer THE FRAMEBUFFER GIVES — two configurations whose
   effective opacity is equal must produce byte-identical pixels, and that
   equality is decided by the renderer, not by this probe's arithmetic.

   The claims, each with the pixel comparison that adjudicates it:

     253 IS NEUTRAL       opa=253 renders identically to opa=255,
                          and neither node emits `opa` at all.
     2 IS TRANSPARENT     opa=2 renders identically to opa=0,
                          and both nodes emit `opa`:0.
     MIX2 CHAINS          a 128 link under a 128 link renders identically to a
                          single 64 link — because MIX2(MIX2(255,128),128) and
                          MIX2(255,64) are both 63 — and both nodes emit
                          `opa`:63. A float model would say 0.25 vs 0.25 and
                          agree here by luck, so the dumped 63 (not 64, not
                          63.75) is the part that discriminates.
     THE CHAIN RAILS TOO  8 under 64, and 16 under 40, have NO link at a rail,
                          yet MIX2 grinds the chain down to 1 and to exactly 2
                          — which the FINAL clamp snaps to transparent. Both
                          exist because they are the only cases that reach that
                          clamp at all: the per-link short-circuit swallows
                          every chain where a SINGLE link is already
                          <= LV_OPA_MIN. Two cases and not one, because they
                          fail to different mutations — deleting the clamp is
                          caught by the 1, and weakening it from `<=` to `<` is
                          caught only by the 2. Both gaps were measured, not
                          reasoned: the first version of this probe had neither
                          case and stayed green through a live `<=`-to-`<`
                          mutation.
     TWO LINKS, ONE SET   LV_STYLE_OPA_LAYERED is a SEPARATE property on the
     OF RAILS             same chain — refr_obj layers the subtree and blends
                          it, rather than scaling each draw — and reading
                          refr_obj alone says its rails DIFFER: there is no
                          `< LV_OPA_MAX` guard, so 253 looks like it should
                          fade. `layered-253` against `opa-255` is the pixel
                          comparison that settles it, and it came back
                          IDENTICAL: the rail is simply one level lower, in the
                          software blenders' `opa >= LV_OPA_MAX` fast path,
                          which copies the source verbatim. This case earns its
                          place by having already overturned a wrong
                          implementation — obj_effective_opa briefly split the
                          two links on that misreading.

   NON-VACUITY: the nested case needs an invisible wrapper, or the wrapper's
   own fade would move pixels and the identity would be testing the wrong
   thing. That is not assumed — case WRAP-INVISIBLE renders the wrapper alone
   and requires it to be byte-identical to no wrapper at all. If that control
   ever fails, the MIX2 result below is meaningless and says so.

   Read-only: builds fixtures in memory, renders, prints. Writes nothing,
   gates nothing, touches no corpus file, and is not part of the battery.

   Run (in the toolchain container, from tools/devcards/):
     clojure -M:bindings:opa-rail-probe"
  (:require [clojure.data.json :as json]
            [devcards.golden :as golden]
            [devcards.host :as host])
  (:import [ui UiAst$Color UiAst$Screen UiAst$StyleGroup UiAst$StyleProperty
            UiAst$StylePropertyType UiAst$StyleVariant UiAst$WidgetNode
            UiAst$WidgetType]))

(set! *warn-on-reflection* true)

(def ^:private canvas {:w 800 :h 480})

(def ^:private int-slots
  "Slots renderer.c reads from the SIGNED oneof arm. It rejects the load
   outright on a wrong arm (`slot_ok`), so this is a closed list, checked
   against the `slot_ok` calls in renderer.c, not a guess."
  #{"PROP_SHADOW_WIDTH" "PROP_OUTLINE_WIDTH"})

(defn- prop
  "One StyleProperty. `v` is an int for every slot used here except
   PROP_BG_COLOR, which takes an [r g b] vector."
  ^UiAst$StyleProperty [slot v]
  (let [b (-> (UiAst$StyleProperty/newBuilder)
              (.setType (UiAst$StylePropertyType/valueOf ^String slot)))]
    (cond
      (vector? v) (.setColorValue b (-> (UiAst$Color/newBuilder)
                                        (.setR (int (nth v 0)))
                                        (.setG (int (nth v 1)))
                                        (.setB (int (nth v 2)))
                                        .build))
      (int-slots slot) (.setIntValue b (int v))
      :else (.setUintValue b (int v)))
    (.build b)))

(defn- node
  "A WIDGET_OBJ carrying `props` ([slot value] pairs) on MAIN|DEFAULT, with
   `children`."
  ^UiAst$WidgetNode [props children]
  (let [vb (UiAst$StyleVariant/newBuilder)
        b (-> (UiAst$WidgetNode/newBuilder)
              (.setType UiAst$WidgetType/WIDGET_OBJ))]
    (.setVariantIndex vb 0)
    (doseq [[slot v] props] (.addProperties vb (prop slot v)))
    (.addStyleGroups b (-> (UiAst$StyleGroup/newBuilder)
                           (.setStateSelector 0)
                           (.addVariants vb)
                           .build))
    (doseq [c children] (.addChildren b ^UiAst$WidgetNode c))
    (.build b)))

(def ^:private no-chrome
  "Slots that suppress every theme-supplied thing a WIDGET_OBJ paints EXCEPT
   its bg fill. A subject then contributes exactly one flat rectangle, and a
   wrapper that also zeroes bg_opa contributes nothing."
  [["PROP_BORDER_WIDTH" 0] ["PROP_PAD_ALL" 0] ["PROP_RADIUS" 0]
   ["PROP_SHADOW_WIDTH" 0] ["PROP_OUTLINE_WIDTH" 0]])

(defn- box
  "The subject: a 200x100 opaque red rectangle, optionally faded by `opa`."
  [opa]
  (node (cond-> (into [["PROP_WIDTH" 200] ["PROP_HEIGHT" 100]
                       ["PROP_BG_COLOR" [220 40 40]] ["PROP_BG_OPA" 255]]
                      no-chrome)
          opa (conj ["PROP_OPA" opa]))
        []))

(defn- wrap
  "An invisible 200x100 container carrying only an optional opa link. `slot`
   selects WHICH link — PROP_OPA scales each child draw's own alpha, while
   PROP_OPA_LAYERED renders the subtree to a layer and blends that. They are
   separate style properties with separate rails."
  ([opa children] (wrap "PROP_OPA" opa children))
  ([slot opa children]
   (node (cond-> (into [["PROP_WIDTH" 200] ["PROP_HEIGHT" 100] ["PROP_BG_OPA" 0]]
                       no-chrome)
           opa (conj [slot opa]))
         children)))

(defn- screen
  "Root-wrap `n` in a full-canvas, pad/border-zeroed WIDGET_OBJ (the harness
   root law) and serialize."
  ^bytes [^UiAst$WidgetNode n]
  (-> (UiAst$Screen/newBuilder)
      (.setRoot (node [["PROP_WIDTH" (:w canvas)] ["PROP_HEIGHT" (:h canvas)]
                       ["PROP_PAD_ALL" 0] ["PROP_BORDER_WIDTH" 0]]
                      [n]))
      .build
      .toByteArray))

(defn- render!
  "One hermetic render: {:sha framebuffer-sha256 :tree parsed-dump}."
  [^bytes pb]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w (:w canvas)
                        :h (:h canvas)})]
    (try {:sha (golden/sha256-hex (host/render-card! h {:pb pb :bp 0 :dark 1}))
          :tree (json/read-str (host/dump-tree! h) :key-fn keyword)}
         (finally (host/close! h)))))

(defn- deepest
  "The last node of a pre-order walk. Every screen here is a single chain, so
   this is the subject."
  [tree]
  (last (tree-seq #(seq (:children %)) :children tree)))

(def ^:private cases
  "id -> [screen-bytes, expected dumped :opa on the deepest node]. `nil` means
   the key must be ABSENT (which the dump defines as LV_OPA_COVER)."
  (array-map
   :bare [(screen (wrap nil [])) nil]
   :wrap-invisible [(screen (wrap 255 [])) nil]
   :opa-255 [(screen (box 255)) nil]
   :opa-253 [(screen (box 253)) nil]
   :opa-0 [(screen (box 0)) 0]
   :opa-2 [(screen (box 2)) 0]
   :nested-128-128 [(screen (wrap 128 [(box 128)])) 63]
   :single-64 [(screen (wrap nil [(box 64)])) 63]
   ;; MIX2(MIX2(255,8),64) = MIX2(7,64) = 1 — no link is at a rail, the CHAIN
   ;; is, and only the final clamp turns that 1 into transparent.
   :nested-8-64 [(screen (wrap 8 [(box 64)])) 0]
   ;; MIX2(MIX2(255,16),40) = MIX2(15,40) = 2 — LV_OPA_MIN EXACTLY, the one
   ;; value that tells `<= LV_OPA_MIN` apart from `< LV_OPA_MIN`.
   :nested-16-40 [(screen (wrap 16 [(box 40)])) 0]
   ;; LV_STYLE_OPA_LAYERED — the SECOND link, with its own rails.
   :layered-255 [(screen (wrap "PROP_OPA_LAYERED" 255 [(box nil)])) nil]
   :layered-2 [(screen (wrap "PROP_OPA_LAYERED" 2 [(box nil)])) 0]
   :layered-128 [(screen (wrap "PROP_OPA_LAYERED" 128 [(box nil)])) 127]
   ;; 253 is neutral for BOTH links — for opa at refr_obj, for opa_layered
   ;; only down in the blender. Measured; see the docstring.
   :layered-253 [(screen (wrap "PROP_OPA_LAYERED" 253 [(box nil)])) nil]))

(defn -main
  [& _]
  (let [res (update-vals cases (fn [[pb _]] (render! pb)))
        opa-of #(:opa (deepest (:tree (get res %))))
        sha-of #(:sha (get res %))
        expect-opa (fn [id]
                     (let [want (second (get cases id))
                           got (opa-of id)]
                       [(= want got) (format "%-16s dumped opa %-6s want %s"
                                             (name id) (pr-str got) (pr-str want))]))
        expect-same (fn [a b why]
                      [(= (sha-of a) (sha-of b))
                       (format "%-16s == %-16s  (%s)" (name a) (name b) why)])
        expect-diff (fn [a b why]
                      [(not= (sha-of a) (sha-of b))
                       (format "%-16s != %-16s  (%s)" (name a) (name b) why)])
        checks
        (concat
         [(expect-same :bare :wrap-invisible
                       "NON-VACUITY CONTROL: the wrapper paints nothing")
          (expect-diff :bare :opa-255
                       "NON-VACUITY CONTROL: the subject paints something")]
         [(expect-same :opa-255 :opa-253 "253 >= LV_OPA_MAX is skipped: neutral")]
         (map expect-opa [:opa-255 :opa-253])
         [(expect-same :opa-0 :opa-2 "2 <= LV_OPA_MIN collapses to transparent")
          (expect-diff :opa-0 :opa-255 "and transparent is not opaque")]
         (map expect-opa [:opa-0 :opa-2])
         [(expect-same :nested-128-128 :single-64
                       "MIX2(MIX2(255,128),128) == MIX2(255,64) == 63")
          (expect-diff :nested-128-128 :opa-255 "and 63 is not COVER")]
         (map expect-opa [:nested-128-128 :single-64])
         [(expect-same :nested-8-64 :opa-0
                       "MIX2(7,64) == 1, which draws nothing")
          (expect-same :nested-16-40 :opa-0
                       "MIX2(15,40) == 2 == LV_OPA_MIN, which draws nothing")]
         (map expect-opa [:nested-8-64 :nested-16-40])
         [(expect-same :layered-255 :opa-255
                       "COVER makes no layer at all: exactly the plain render")
          (expect-same :layered-2 :opa-0
                       "refr_obj returns before drawing at <= LV_OPA_MIN")
          (expect-diff :layered-128 :opa-255 "128 really fades the layer")
          (expect-diff :layered-128 :opa-0 "and does not erase it")
          ;; THE DISCRIMINATOR, and it overturned the implementation once:
          ;; refr_obj has no >= LV_OPA_MAX skip for the layered link, so this
          ;; SHOULD differ by that reading. It does not, because the blenders
          ;; snap it — which is why obj_effective_opa folds both links alike.
          (expect-same :layered-253 :opa-255
                       "the blender's >= LV_OPA_MAX fast path snaps 253 to cover")]
         (map expect-opa [:layered-255 :layered-2 :layered-128 :layered-253]))]

    (println "\n══ OPA RAIL PROBE ══")
    (doseq [[ok msg] checks]
      (println (format "  %s  %s" (if ok "PASS" "FAIL") msg)))
    (println "\n══ FRAMEBUFFER SHAS ══")
    (doseq [[id _] cases]
      (println (format "  %-16s %s" (name id) (subs (sha-of id) 0 16))))
    (let [bad (count (remove first checks))]
      (println (format "\n%d/%d checks passed" (- (count checks) bad) (count checks)))
      (when (pos? bad) (System/exit 1)))))
