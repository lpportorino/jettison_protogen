(ns roller-family-probe
  "Empirical probe: what does each THEME FAMILY do to a roller under DISABLED?

   Task #104 asks whether the two shipped families disagree about a disabled
   roller's selection cue. Reading `renderer/src/theme.c` alone cannot answer
   it: the asgard arm is explicit, but the vanilla arm is an ABSENCE, and what
   an absence renders is whatever the vendored stock parent theme does. Only
   the framebuffer knows.

   METHOD, per (card, family, mode):
     - render hermetically (fresh context, family set BEFORE the pinned render
       protocol, exactly as devcards.core/render-one! does);
     - locate the `lv_roller` node's `:coords` in the dump tree;
     - take the MODAL colour of every scanline inside that box, SrcOver-
       flattened onto black exactly as devcards.jpeg does, and run-length
       encode it. A selected band that is a distinct fill shows up as its own
       run; a band that has drained into the field merges and the profile
       correctly reports ONE fill.
     - report the WCAG contrast between the widest run (the field) and every
       other run (candidate bands).

   THE DISCRIMINATOR IS THE PAIR, NOT THE CARD. `lv_roller/<state>/medium/mid`
   differs from its `default` twin in exactly one property (`:states-bits`
   512 vs 0), so a byte comparison of the two renders under ONE family is a
   clean answer to `does DISABLED change anything at all here'. That is the
   measurement `theme.c` cannot be read for.

   Read-only: renders, measures, prints. Writes nothing, gates nothing.

   Run (in the toolchain container, from tools/devcards/):
     clojure -Sdeps '{:aliases {:probe {:extra-paths [\"dev\"]}}}' \\
       -M:bindings:probe -m roller-family-probe"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [devcards.fixtures :as fixtures]
            [devcards.host :as host])
  (:import (java.security MessageDigest)))

(set! *warn-on-reflection* true)

(def ^:private canvas {:w 800 :h 480})

(def ^:private families
  [{:id 0 :name "asgard"} {:id 1 :name "vanilla"} {:id 2 :name "stock"}])

(def ^:private default-pairs
  "[enabled-card disabled-card] twins that differ only in :states-bits."
  [["lv_roller/default/medium/mid" "lv_roller/disabled/medium/mid"]
   ["lv_roller/default/small/mid" "lv_roller/disabled/small/mid"]
   ["lv_roller/default/large/mid" "lv_roller/disabled/large/mid"]
   ["lv_roller/default/medium/min" "lv_roller/disabled/medium/min"]
   ["lv_roller/default/medium/max" "lv_roller/disabled/medium/max"]])

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
  (let [d (.digest (MessageDigest/getInstance "SHA-256") b)]
    (str/join (map #(format "%02x" %) d))))

(defn- flat-px
  "SrcOver-flatten one pixel onto black."
  [^bytes raw w x y]
  (let [i (* 4 (+ (* (long y) (long w)) (long x)))
        a (bit-and (aget raw (+ i 3)) 0xFF)]
    [(quot (* (bit-and (aget raw i) 0xFF) a) 255)
     (quot (* (bit-and (aget raw (+ i 1)) 0xFF) a) 255)
     (quot (* (bit-and (aget raw (+ i 2)) 0xFF) a) 255)]))

(defn- modal-of-scanline
  [^bytes raw w y x0 x1]
  (let [hist (persistent!
              (reduce (fn [acc x]
                        (let [px (flat-px raw w x y)]
                          (assoc! acc px (inc (long (get acc px 0))))))
                      (transient {})
                      (range (long x0) (inc (long x1)))))]
    (key (apply max-key val hist))))

(defn- rle [pairs]
  (reduce (fn [acc [y v]]
            (let [[pv _ _ :as prev] (peek acc)]
              (if (and prev (= pv v))
                (conj (pop acc) [pv (nth prev 1) y])
                (conj acc [v y y]))))
          []
          pairs))

(defn- walk [root] (tree-seq #(seq (:children %)) :children root))

(defn- box-bytes
  "The raw (unflattened) RGBA bytes inside the box — the byte-comparison input."
  ^bytes [^bytes raw ^long w [x0 y0 x1 y1]]
  (let [out (byte-array (* 4 (inc (- (long x1) (long x0))) (inc (- (long y1) (long y0)))))]
    (loop [y (long y0) o 0]
      (if (> y (long y1))
        out
        (recur (inc y)
               (long (loop [x (long x0) o (long o)]
                       (if (> x (long x1))
                         o
                         (let [i (* 4 (+ (* y w) x))]
                           (System/arraycopy raw i out o 4)
                           (recur (inc x) (+ o 4)))))))))))

(defn- render!
  [^bytes pb family dark]
  (let [h (host/start! {:wasm "../../renderer/output/controls.wasm"
                        :assets "../../renderer/assets"
                        :w (:w canvas) :h (:h canvas)})]
    (try
      (when (pos? (long family)) (host/set-theme-family! h family))
      (let [fb (host/render-card! h {:pb pb :bp 0 :dark (if dark 1 0)})
            tree (json/read-str (host/dump-tree! h) :key-fn keyword)
            roller (first (filter #(= "lv_roller" (:type %)) (walk tree)))]
        {:fb fb :coords (:coords roller) :tree tree})
      (finally (host/close! h)))))

(defn- profile
  [^bytes fb coords]
  (let [w (long (:w canvas))
        [rx0 ry0 rx1 ry1] (mapv long coords)
        x0 (max 0 rx0) y0 (max 0 ry0)
        x1 (min (dec w) rx1) y1 (min (dec (long (:h canvas))) ry1)
        lines (mapv (fn [y] [y (modal-of-scanline fb w y x0 x1)]) (range y0 (inc y1)))
        runs (rle lines)
        widest (apply max-key (fn [[_ a b]] (- (long b) (long a))) runs)]
    {:runs runs
     :field (first widest)
     :box [x0 y0 x1 y1]}))

(defn- fmt-runs [runs field]
  (str/join "  "
            (map (fn [[v a b]]
                   (format "%d-%d %s%s" a b (hexof v)
                           (if (= v field)
                             ""
                             (format "(x%.2f)" (contrast v field)))))
                 runs)))

(defn -main [& _args]
  (let [spec (fixtures/load-spec)
        built (into {} (map (juxt (comp str :id) identity)) (fixtures/build-all spec))]
    (println (format "corpus cards: %d" (count built)))
    (doseq [[on off] default-pairs
            {fam :id fname :name} families
            dark [true false]]
      (let [ce (get built on) cd (get built off)]
        (if-not (and ce cd)
          (println (format "MISSING PAIR %s / %s" on off))
          (let [re (render! (:bytes ce) fam dark)
                rd (render! (:bytes cd) fam dark)
                mode (if dark "dark" "light")]
            (println (format "\n=== %s  family=%d(%s) %s" off fam fname mode))
            (if-not (and (:coords re) (:coords rd))
              (println "  NO lv_roller NODE in one of the two dumps")
              (let [pe (profile (:fb re) (:coords re))
                    pd (profile (:fb rd) (:coords rd))
                    same-box (= (:coords re) (:coords rd))
                    be (box-bytes (:fb re) (long (:w canvas)) (:box pe))
                    bd (box-bytes (:fb rd) (long (:w canvas)) (:box pd))
                    ndiff (when same-box
                            (count (filter false? (map = (seq be) (seq bd)))))]
                (println (format "  coords  enabled %s   disabled %s   same-box=%s"
                                 (pr-str (:coords re)) (pr-str (:coords rd)) same-box))
                (println (format "  ENABLED  field=%s  %s"
                                 (hexof (:field pe)) (fmt-runs (:runs pe) (:field pe))))
                (println (format "  DISABLED field=%s  %s"
                                 (hexof (:field pd)) (fmt-runs (:runs pd) (:field pd))))
                (println (format "  fb-sha   enabled %s  disabled %s  EQUAL=%s"
                                 (subs (sha256 (:fb re)) 0 12)
                                 (subs (sha256 (:fb rd)) 0 12)
                                 (= (sha256 (:fb re)) (sha256 (:fb rd)))))
                (println (format "  roller-box differing BYTES enabled-vs-disabled: %s"
                                 (pr-str ndiff)))
                (println (format "  DISABLED band runs (fills != field): %d"
                                 (count (remove (fn [[v _ _]] (= v (:field pd))) (:runs pd)))))))))))
    (println (str "\nA disabled card whose roller box is byte-identical to its enabled twin\n"
                  "carries NO disabled signal at all in that family. A disabled card with\n"
                  "zero band runs carries no SELECTION cue. They are different questions."))))
