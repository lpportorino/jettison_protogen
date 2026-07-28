(ns cascade-census
  "Read-only census: run `devcards.cascade` over the whole shipped corpus on
   the real wasm, and report how much of it the dump can actually determine.

   This is the arming/sizing measurement for anything that wants per-node
   foreground/background pairs. It gates nothing and writes nothing. Its point
   is that the headline is NEGATIVE: the resolver's UNKNOWN share is the size
   of the hole a static readability tier would inherit, and reading it off the
   C rather than measuring it is how that hole gets under-estimated.

   Run (in the toolchain container, from tools/devcards/):
     clojure -Sdeps '{:aliases {:probe {:extra-paths [\"../../.fork-scratch\"]}}}' \\
       -M:bindings:probe -m cascade-census"
  (:require [clojure.data.json :as json]
            [devcards.cascade :as cascade]
            [devcards.composition :as composition]
            [devcards.fixtures :as fixtures]
            [devcards.host :as host]))

(set! *warn-on-reflection* true)

(def ^:private canvas {:w 800 :h 480})

(defn- render+dump!
  [^bytes pb dark]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w (:w canvas)
                        :h (:h canvas)})]
    (try (host/render-card! h {:pb pb :bp 0 :dark (if dark 1 0)})
         (json/read-str (host/dump-tree! h) :key-fn keyword)
         (finally (host/close! h)))))

(defn -main
  [& _]
  (let [spec (fixtures/load-spec)
        built (concat (fixtures/build-all spec)
                      (composition/build-all (composition/load-inventory)))
        _ (println (format "rendering %d cards x 2 themes…" (count built)))
        es (into []
                 (for [{:keys [id] ^bytes pb :bytes} built
                       mode [:dark :light]
                       :let [tree (render+dump! pb (= mode :dark))]
                       e (cascade/resolve-tree tree)]
                   (assoc e :card (str id "|" (name mode)))))
        n (count es)]
    (println (format "\n%d nodes over %d renders" n (* 2 (count built))))

    (println "\n══ OUTCOME ══")
    (doseq [[o g] (sort-by key (group-by :outcome es))]
      (println (format "  %-12s %6d  %5.1f%%" (str o) (count g)
                       (* 100.0 (/ (count g) (double n))))))

    (println "\n══ GLYPH AXIS (separate from the pair) ══")
    (doseq [[g grp] (sort-by key (group-by :glyphs es))]
      (println (format "  %-10s %6d" (str g) (count grp))))

    (println "\n══ WHY UNKNOWN — reason occurrences (an entry may carry several) ══")
    (doseq [[r c] (sort-by (comp - val) (frequencies (mapcat :reasons es)))]
      (println (format "  %-28s %6d" (str r) c)))

    (println "\n══ UNKNOWN by class ══")
    (doseq [[t c] (sort-by (comp - val)
                           (frequencies (map :type (filter #(= :unknown (:outcome %)) es))))]
      (println (format "  %-24s %6d" t c)))

    (println "\n══ RESOLVED by class ══")
    (doseq [[t c] (sort-by (comp - val)
                           (frequencies (map :type (filter #(= :resolved (:outcome %)) es))))]
      (println (format "  %-24s %6d" t c)))

    (println "\n══ THE SILENT CLASSES: glyph-bearing, and what the dump says ══")
    (println "  (`obj_draws_text` uses lv_obj_check_type — EXACT class equality —")
    (println "   so these four draw glyphs and can never carry backdrop_unresolved)")
    (doseq [t ["lv_roller_label" "lv_dropdown-list" "lv_textarea" "lv_spinbox"]]
      (let [sub (filter #(= t (:type %)) es)]
        (println (format "  %-18s n=%-5d resolved=%-5d unknown=%-5d no-glyphs=%-5d"
                         t (count sub)
                         (count (filter #(= :resolved (:outcome %)) sub))
                         (count (filter #(= :unknown (:outcome %)) sub))
                         (count (filter #(= :no-glyphs (:outcome %)) sub))))))

    (println "\n══ INTERPRETER/CLASS-SET DISAGREEMENT ══")
    (let [d (filter :interpreter-claims-glyphs? es)]
      (println (format "  %d node(s): %s" (count d)
                       (pr-str (frequencies (map :type d)))))
      (println "  (backdrop_unresolved on a class devcards.opa calls text-free)"))

    (println "\n══ CONTROL: is the resolver discriminating, or does it answer")
    (println "   the same thing everywhere? ══")
    (println (format "  distinct (outcome,reasons) shapes: %d"
                     (count (distinct (map (juxt :outcome :reasons) es)))))
    (println (format "  distinct resolved (fg,bg) pairs:   %d"
                     (count (distinct (map (juxt (comp :hex :fg) (comp :hex :bg))
                                           (filter #(= :resolved (:outcome %)) es))))))
    (println "  (a resolver that returned one answer everywhere would show 1)")
    (println "\ndone")))
