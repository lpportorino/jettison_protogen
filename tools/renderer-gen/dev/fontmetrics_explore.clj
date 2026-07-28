;; .fork-scratch/fontmetrics_explore.clj — exploratory only (fork scratch).
;; Computes, for each vendored TTF, the line_height / base_line lv_tiny_ttf
;; WOULD produce at each compiled size, so the compiled-vs-TTF disagreement can
;; be seen before any assertion is written.
;; Run: tools/uber.sh 'cd tools/renderer-gen && clojure -M dev/fontmetrics_explore.clj'
(require '[clojure.java.io :as io])

(def repo-root "../..")

(defn- read-bytes ^bytes [f]
  (with-open [in (io/input-stream f)
              out (java.io.ByteArrayOutputStream.)]
    (io/copy in out)
    (.toByteArray out)))

(defn- u8 [^bytes b i] (bit-and (int (aget b (int i))) 0xFF))
(defn- u16 [^bytes b i] (bit-or (bit-shift-left (u8 b i) 8) (u8 b (inc i))))
(defn- s16 [^bytes b i] (let [v (u16 b i)] (if (>= v 0x8000) (- v 0x10000) v)))
(defn- u32 [^bytes b i]
  (bit-or (bit-shift-left (u16 b i) 16) (u16 b (+ i 2))))

(defn- table-offsets [^bytes b]
  (let [n (u16 b 4)]
    (into {}
          (for [i (range n)
                :let [e (+ 12 (* 16 i))]]
            [(String. b e 4 "ISO-8859-1") (u32 b (+ e 8))]))))

(defn ttf-vmetrics [path]
  (let [b (read-bytes path)
        t (table-offsets b)
        head (get t "head")
        hhea (get t "hhea")]
    {:units-per-em (u16 b (+ head 18))
     :ascent (s16 b (+ hhea 4))
     :descent (s16 b (+ hhea 6))
     :line-gap (s16 b (+ hhea 8))}))

(defn tiny-ttf-metrics
  "The exact lv_tiny_ttf.c derivation (lines 159-163), float maths, C truncation."
  [{:keys [units-per-em ascent descent line-gap]} size]
  (let [scale (float (/ (float size) (float units-per-em)))]
    {:line-height (long (float (* scale (float (- (+ ascent line-gap) descent)))))
     :base-line (long (float (* scale (float (- line-gap descent)))))
     :line-height-exact (double (* scale (- (+ ascent line-gap) descent)))
     :base-line-exact (double (* scale (- line-gap descent)))}))

(defn compiled [sym]
  (let [f (first (filter #(.isFile (io/file %))
                         [(str repo-root "/renderer/src/" sym ".c")
                          (str repo-root "/renderer/lvgl/src/font/" sym ".c")]))
        txt (slurp f)]
    {:line-height (Long/parseLong (second (re-find #"\.line_height\s*=\s*(-?\d+)" txt)))
     :base-line (Long/parseLong (second (re-find #"\.base_line\s*=\s*(-?\d+)" txt)))}))

(let [b612 (ttf-vmetrics (str repo-root "/renderer/assets/fonts/b612mono_bold.ttf"))
      orb (ttf-vmetrics (str repo-root "/renderer/assets/fonts/Orbitron-Bold.ttf"))]
  (println "b612mono_bold.ttf hhea:" b612)
  (println "Orbitron-Bold.ttf hhea:" orb)
  (println)
  (printf "%-26s %5s %5s | %5s %5s | %9s %9s%n"
          "font" "cLH" "cBL" "tLH" "tBL" "exactLH" "exactBL")
  (doseq [[fam v sizes] [["b612mono_bold" b612 [12 14 16 18 20 26]]
                         ["orbitron_bold" orb [22 28 32]]]
          size sizes]
    (let [sym (str "font_" fam "_" size)
          has-table (.isFile (io/file (str repo-root "/renderer/src/" sym ".c")))
          c (when has-table (compiled sym))
          t (tiny-ttf-metrics v size)]
      (printf "%-26s %5s %5s | %5d %5d | %9.3f %9.3f%n"
              (str fam "_" size)
              (str (:line-height c)) (str (:base-line c))
              (:line-height t) (:base-line t)
              (:line-height-exact t) (:base-line-exact t)))))
