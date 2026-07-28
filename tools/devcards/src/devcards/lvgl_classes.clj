(ns devcards.lvgl-classes
  "The STARTER classification table for the LVGL classes this renderer
   emits — the table a consumer overrides rather than authors from zero.

   `devcards.classify` makes an undeclared type a finding, which is right
   (an unjudged element must never pass silently) but means a consumer's
   first run is otherwise a wall of :unclassified-type. protogen owns the
   vocabulary, so the starter set belongs here.

   ── :interactive? IS MECHANISM, NOT INTENT ────────────────────────────
   It answers ONE question: does LVGL put this class in the pointer path?
   That is `LV_OBJ_FLAG_CLICKABLE`, which `lv_obj_constructor` sets on
   EVERY object (lv_obj.c:584).

   Scoped to the classes THIS renderer emits (`emitted-classes`), five
   clear it in their constructors: lv_image, lv_label, lv_line, lv_spinner,
   and lv_roller_label via its lv_label base. Every other emitted class
   inherits the default and CAN take a press.

   That is a claim about the EMITTED set, not about LVGL as a whole, and
   the difference is not pedantry — lv_arclabel also clears the flag and
   lv_menu adds and removes it per instance across a dozen sites. Neither
   is emitted here today. A widget added to the corpus therefore owes this
   derivation again; the anti-rot gate will demand a row, but only a human
   can decide what goes in it.

   That is deliberately not the same as 'is a control'. An lv_bar is a
   read-only indicator by intent and still clickable by mechanism, so it
   is :interactive? true here. Encoding intent instead would silence the
   real case where a bar is stacked ON TOP of a control and kills it —
   which is the entire hazard the overlap rule exists for. Intent is what
   :role carries, and the layer contract is what resolves a stack that is
   deliberate; neither is a reason to lie about who can take the pointer.

   Measured consequence, so this is not theoretical: under this table the
   scrubber lego reports lv_bar vs lv_slider. That is a TRUE pointer-path
   collision. It happens to be benign only because the bar is the earlier
   sibling and `lv_indev_search_obj` walks children in REVERSE, so the
   slider wins — which is a fact about paint order, exactly the thing the
   layer contract declares rather than assumes. Overlap alone cannot tell
   'benign because it is underneath' from 'broken'; that is an argument
   for landing the layer contract, not for weakening this table.

   ── :role IS INTENT, and its three values are load-bearing ────────────
   :text and :interactive are what a human READS or AIMS AT — any
   occlusion of them is damage. :structural is chrome whose partial
   covering is ordinary composition.

   KNOWN TENSION, flagged rather than silently resolved: the closed role set
   was fixed against a far narrower census than the class set this renderer
   actually emits — run the `class-census` probe for both live counts rather
   than trusting a number copied here, which rots the next time a widget
   lands. The
   name :text reads as 'glyphs', but the property that matters is 'a human
   reads this, so occlusion is damage' — which is equally true of a bar, a
   gauge, an LED and a table. They are :text here on the semantic, not the
   name. Whether that set needs a fourth member is a real open question,
   and mislabelling is cheaper to correct than a wrong threshold arm.

   Per-INSTANCE facts are never in this table. A STATIC host_proxy box has
   CLICKABLE cleared at runtime (`proxy_apply_mode` in
   `renderer/src/renderer.c`) and a disabled widget
   keeps it; both are read off the node, not looked up here."
  (:require [devcards.classify :as classify]))

(set! *warn-on-reflection* true)

(def ^:private not-in-pointer-path
  "The four classes whose constructors clear LV_OBJ_FLAG_CLICKABLE, plus
   lv_roller_label, whose class derives from lv_label and therefore runs
   the label constructor that clears it. Everything else inherits
   lv_obj_constructor's CLICKABLE default."
  #{"lv_image" "lv_label" "lv_line" "lv_spinner" "lv_roller_label"})

(def ^:private roles
  "Intent per class. Grouped rather than listed one-per-line so a new
   widget joins a stated category instead of getting a bare keyword."
  {:interactive #{"lv_arc" "lv_button" "lv_buttonmatrix" "lv_checkbox"
                  "lv_dropdown" "lv_dropdown-list" "lv_roller" "lv_slider"
                  "lv_spinbox" "lv_switch" "lv_textarea"}
   ;; read for a VALUE — occlusion is damage, glyphs or not
   :text #{"lv_bar" "lv_chart" "lv_label" "lv_led" "lv_roller_label"
           "lv_scale" "lv_table"}
   ;; chrome, surfaces and busy-indicators — partial covering is composition
   :structural #{"lv_image" "lv_line" "lv_obj" "lv_spinner" "lv_tabview"}})

(def emitted-classes
  "Every class the renderer emits as a dump `type` over the committed
   corpus, measured by the `class-census` dev probe rather than guessed.
   Two are absent from `corpus/spec.edn`'s tags because the renderer
   builds them internally — lv_roller_label and lv_dropdown-list, the
   latter spelled with a HYPHEN where the roller's uses an underscore.
   Guessing that pair of spellings is precisely what this table exists to
   spare a consumer."
  (into (sorted-set) (mapcat val) roles))

(def starter-table
  "The table itself, in `devcards.classify` shape. No :default — totality
   is enforced, so a class this set does not name is reported rather than
   assumed harmless. A consumer merges its own widgets over :types."
  (classify/validate-table!
   {:types (into {}
                 (for [[role cls] roles
                       c cls]
                   [c {:role role
                       :interactive? (not (contains? not-in-pointer-path c))}]))}))

(defn merge-consumer
  "The starter table with a consumer's own :types merged OVER it, so a
   consumer both inherits the LVGL set and can correct any entry for its
   own widgets. Validates the result — a merge that produces a
   contradiction fails here, not at judgment time."
  [consumer-table]
  (classify/validate-table!
   (-> starter-table
       (update :types merge (:types consumer-table))
       (cond-> (:default consumer-table)
         (assoc :default (:default consumer-table))))))
