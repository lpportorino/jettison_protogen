(ns roller-ink-probe
  "Two follow-on measurements for task #104, both from the framebuffer.

   (A) INSIDE one disabled asgard roller, is the SELECTED row distinguished
       from its neighbours by anything other than position? The fill profile
       already showed one uniform fill; this adds the INK: per scanline, the
       count of non-modal pixels and the ink colour furthest in luminance from
       that scanline's modal fill. If the centre row's ink hex equals the
       neighbours' ink hex, the only remaining cue is geometry.

   (B) DOES THE CHECKED CUE SURVIVE `DISABLED` ELSEWHERE IN THE SAME FAMILY?
       The corpus ships `<class>/disabled/...` and `<class>/{disabled-checked,
       checked-disabled}/...` twins for switch and checkbox — widgets whose
       DISABLED treatment is the FADE (`disabled_dim`), not the pair swap. A
       byte comparison of those two renders says whether asgard still expresses
       CHECKED once the widget is disabled. That is the generalisation test the
       roller question turns on: if the cue survives elsewhere the roller is the
       outlier, and if it dies everywhere the roller is consistent.

   Read-only: renders, measures, prints. Writes nothing, gates nothing.

   Run (in the toolchain container, from tools/devcards/):
     clojure -Sdeps '{:aliases {:probe {:extra-paths [\"dev\"]}}}' \\
       -M:bindings:probe -m roller-ink-probe"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [devcards.fixtures :as fixtures]
            [devcards.host :as host])
  (:import (java.security MessageDigest)))

(set! *warn-on-reflection* true)

(def ^:private canvas {:w 800 :h 480})

(def ^:private ink-floor
  "Minimum non-modal pixels for a scanline to count as carrying a GLYPH rather
   than an anti-aliased fringe. Same order of magnitude as
   dev/disabled_pair_probe.clj and dev/roller_bounds_probe.clj."
  8)

(def ^:private ink-cards
  ["lv_roller/default/medium/mid" "lv_roller/disabled/medium/mid"])

(def ^:private checked-twins
  [["lv_switch/disabled/small" "lv_switch/disabled-checked/small"]
   ["lv_switch/disabled/medium" "lv_switch/disabled-checked/medium"]
   ["lv_switch/disabled/large" "lv_switch/disabled-checked/large"]
   ["lv_checkbox/disabled/medium" "lv_checkbox/checked-disabled/medium"]])

(defn- lin ^double [^long c]
  (let [c (/ (double c) 255.0)]
    (if (<= c 0.03928) (/ c 12.92) (Math/pow (/ (+ c 0.055) 1.055) 2.4))))

(defn- luminance ^double [[r g b]]
  (+ (* 0.2126 (lin r)) (* 0.7152 (lin g)) (* 0.0722 (lin b))))

(defn- contrast ^double [a b]
  (let [la (luminance a) lb (luminance b)]
    (/ (+ (max la lb) 0.05) (+ (min la lb) 0.05))))

(defn- hexof [[r g b]] (format "#%02X%02X%02X" r g b))

(defn- sha256 ^String [^bytes b]
  (str/join (map #(format "%02x" %)
                 (.digest (MessageDigest/getInstance "SHA-256") b))))

(defn- flat-px [^bytes raw w x y]
  (let [i (* 4 (+ (* (long y) (long w)) (long x)))
        a (bit-and (aget raw (+ i 3)) 0xFF)]
    [(quot (* (bit-and (aget raw i) 0xFF) a) 255)
     (quot (* (bit-and (aget raw (+ i 1)) 0xFF) a) 255)
     (quot (* (bit-and (aget raw (+ i 2)) 0xFF) a) 255)]))

(defn- scan
  "[modal ink-count extreme-ink] for one scanline, where extreme-ink is the
   non-modal colour furthest in luminance from the modal fill."
  [^bytes raw w y x0 x1]
  (let [hist (persistent!
              (reduce (fn [acc x]
                        (let [px (flat-px raw w x y)]
                          (assoc! acc px (inc (long (get acc px 0))))))
                      (transient {})
                      (range (long x0) (inc (long x1)))))
        [modal modal-n] (apply max-key val hist)
        total (inc (- (long x1) (long x0)))
        others (dissoc hist modal)
        ext (when (seq others)
              (key (apply max-key
                          (fn [[c _]] (Math/abs (- (luminance c) (luminance modal))))
                          others)))]
    [modal (- total (long modal-n)) ext]))

(defn- walk [root] (tree-seq #(seq (:children %)) :children root))

(defn- render!
  [^bytes pb family dark node-type]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w (:w canvas) :h (:h canvas)})]
    (try
      (when (pos? (long family)) (host/set-theme-family! h family))
      (let [fb (host/render-card! h {:pb pb :bp 0 :dark (if dark 1 0)})
            tree (json/read-str (host/dump-tree! h) :key-fn keyword)
            n (first (filter #(= node-type (:type %)) (walk tree)))]
        {:fb fb :coords (:coords n)})
      (finally (host/close! h)))))

(defn -main [& _args]
  (let [spec (fixtures/load-spec)
        built (into {} (map (juxt (comp str :id) identity)) (fixtures/build-all spec))]
    (println "===== (A) per-scanline INK inside the roller box, family 0 =====")
    (doseq [id ink-cards
            dark [true false]]
      (let [{:keys [fb coords]} (render! (:bytes (get built id)) 0 dark "lv_roller")
            w (long (:w canvas))
            [x0 y0 x1 y1] (mapv long coords)
            rows (mapv (fn [y] (into [y] (scan fb w y x0 x1))) (range y0 (inc y1)))
            inked (filterv (fn [[_ _ n _]] (>= (long n) ink-floor)) rows)]
        (println (format "\n--- %s [%s]  coords %s" id (if dark "dark" "light") (pr-str coords)))
        (println (format "    inked scanlines: %d of %d" (count inked) (count rows)))
        (doseq [[y modal n ext] inked]
          (println (format "    y=%3d fill %s ink %s (n=%3d, x%.2f)"
                           y (hexof modal) (hexof ext) n (contrast ext modal))))
        (println (format "    DISTINCT (fill, ink) pairs over inked rows: %s"
                         (pr-str (into (sorted-set)
                                       (map (fn [[_ m _ e]] [(hexof m) (hexof e)]) inked)))))))
    (println "\n===== (B) does CHECKED survive DISABLED elsewhere in family 0? =====")
    (doseq [[unchecked checked] checked-twins
            dark [true false]]
      (let [a (get built unchecked) b (get built checked)]
        (if-not (and a b)
          (println (format "MISSING TWIN %s / %s" unchecked checked))
          (let [ra (render! (:bytes a) 0 dark "lv_obj")
                rb (render! (:bytes b) 0 dark "lv_obj")
                sa (sha256 (:fb ra)) sb (sha256 (:fb rb))
                ndiff (count (filter false? (map = (seq (:fb ra)) (seq (:fb rb)))))]
            (println (format "  %-40s vs %-42s [%s]  EQUAL=%s  differing-bytes=%d"
                             unchecked checked (if dark "dark" "light")
                             (= sa sb) ndiff))))))
    (println (str "\n(A) one distinct (fill, ink) pair over every inked row means the\n"
                  "    selected row is typographically identical to its neighbours.\n"
                  "(B) EQUAL=false means asgard still expresses CHECKED under DISABLED\n"
                  "    for that class."))))
