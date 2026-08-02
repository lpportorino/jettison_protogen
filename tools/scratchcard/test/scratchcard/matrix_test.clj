(ns scratchcard.matrix-test
  "Pins the render matrix — and pins the C constants it mirrors.

  `mirrored-disp-thresholds-still-match-the-c-source` is the important one.
  The display tier is not in `dump_tree` and cannot be read at runtime, so a
  mirror is unavoidable; what is avoidable is a mirror that drifts silently.
  This test greps the vendored LVGL source, so an upstream bump that moves the
  thresholds fails HERE with a message, instead of relabelling every render in
  every archived run."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [scratchcard.matrix :as matrix]
   [scratchcard.scope :as scope]))

(def ^:private repo-root
  (scope/discover-repo-root (System/getProperty "user.dir")))

(deftest mirrored-disp-thresholds-still-match-the-c-source
  (let [src (io/file repo-root "renderer" "lvgl" "src" "themes" "default"
                     "lv_theme_default.c")]
    (is (.isFile src) "the vendored LVGL default theme must be readable")
    (let [text (slurp src)
          small (re-find #"greater_res\s*<=\s*(\d+)\)\s*new_size\s*=\s*DISP_SMALL" text)
          large (re-find #"greater_res\s*<\s*(\d+)\)\s*new_size\s*=\s*DISP_MEDIUM" text)]
      (testing "the clauses are still shaped the way this mirror assumes"
        ;; If these go nil the grep found nothing — which must FAIL, not
        ;; silently pass an equality against nil.
        (is (some? small) "DISP_SMALL threshold clause not found in the C source")
        (is (some? large) "DISP_MEDIUM threshold clause not found in the C source"))
      (testing "and the numbers still agree with ours"
        (is (= matrix/disp-small-max (parse-long (second small))))
        (is (= matrix/disp-large-min (parse-long (second large))))))))

(deftest disp-tier-uses-the-greater-axis
  (testing "the boundaries, from both sides"
    (is (= "DISP_SMALL" (matrix/disp-tier 320 240)))
    (is (= "DISP_MEDIUM" (matrix/disp-tier 321 240)))
    (is (= "DISP_MEDIUM" (matrix/disp-tier 719 480)))
    (is (= "DISP_LARGE" (matrix/disp-tier 720 480))))
  (testing "orientation does not change the tier — it is the GREATER axis"
    (is (= (matrix/disp-tier 390 844) (matrix/disp-tier 844 390)))
    (is (= "DISP_LARGE" (matrix/disp-tier 390 844))))
  (testing "the corpus canvas is DISP_LARGE, which is why it was chosen"
    (is (= "DISP_LARGE" (matrix/disp-tier 800 480)))))

(deftest default-set-spans-the-tiers-it-claims
  (let [tiers (into #{} (map #(matrix/disp-tier (:w %) (:h %))) matrix/default-resolutions)]
    (is (contains? tiers "DISP_LARGE"))
    (testing "640x480 is the MEDIUM crossing the docstring promises"
      (is (contains? tiers "DISP_MEDIUM")))
    (testing "and the documented GAP is real — the default set never reaches SMALL"
      ;; Asserted so the docstring's coverage note cannot quietly become false
      ;; if someone adds a tiny canvas without updating it.
      (is (not (contains? tiers "DISP_SMALL"))))))

(deftest breakpoint-tiers-follow-the-token-table
  (is (= 0 (matrix/bp-for-width 0)))
  (is (= 0 (matrix/bp-for-width 639)))
  (is (= 1 (matrix/bp-for-width 640)))
  (is (= 1 (matrix/bp-for-width 1023)))
  (is (= 2 (matrix/bp-for-width 1024)))
  (is (= 2 (matrix/bp-for-width 1279)))
  (is (= 3 (matrix/bp-for-width 1280)))
  (is (= 3 (matrix/bp-for-width 1920)))
  (testing "every tier index is inside the range controls_set_breakpoint accepts"
    (is (every? #(<= 0 (matrix/bp-for-width %) 3) [0 640 1024 1280 16384]))))

(deftest expand-produces-the-full-matrix-in-a-stable-order
  (let [cells (matrix/expand {})]
    (testing "6 theme states x the default canvases"
      (is (= (matrix/expected-count {}) (count cells)))
      (is (= (* 6 (count matrix/default-resolutions)) (count cells))))
    (testing "every cell is distinct"
      (is (= (count cells)
             (count (set (map (juxt :family :dark :w :h) cells))))))
    (testing "all six theme states appear for every canvas"
      (is (= 6 (count (set (map (juxt :family :dark) cells))))))
    (testing "bp is pinned at 0 by default — matching every other render here"
      (is (= #{0} (set (map :bp cells)))))
    (testing "the order is deterministic across calls"
      (is (= cells (matrix/expand {}))))
    (testing "each cell carries its resolved tier"
      (is (every? #(contains? #{"DISP_SMALL" "DISP_MEDIUM" "DISP_LARGE"} (:disp-tier %))
                  cells)))))

(deftest expand-honours-a-caller-supplied-set
  (let [cells (matrix/expand {:resolutions [{:w 800 :h 480}]})]
    (is (= 6 (count cells)))
    (is (= #{[800 480]} (set (map (juxt :w :h) cells)))))
  (testing "families and modes can be narrowed too"
    (let [cells (matrix/expand {:resolutions [{:w 800 :h 480}]
                                :families [0] :modes [1]})]
      (is (= 1 (count cells)))
      (is (= {:family 0 :dark 1 :w 800 :h 480 :bp 0 :disp-tier "DISP_LARGE"}
             (first cells)))))
  (testing "bp-from-canvas? sweeps the token tiers instead of pinning 0"
    (let [cells (matrix/expand {:resolutions [{:w 1920 :h 1080} {:w 800 :h 480}]
                                :families [0] :modes [1]
                                :bp-from-canvas? true})]
      (is (= [3 1] (mapv :bp cells))))))

(deftest an-empty-or-invalid-matrix-fails-loud
  ;; A green tick over zero renders actively asserts what it never looked at.
  (testing "an explicitly empty set is refused, never silently defaulted"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (matrix/validate-resolutions! [])))]
      (is (= "EMPTY_MATRIX" (:error (ex-data e))))))
  (testing "non-positive dimensions"
    (is (some? (matrix/resolution-problem {:w 0 :h 480})))
    (is (some? (matrix/resolution-problem {:w 800 :h -1}))))
  (testing "beyond CONTROLS_MAX_DIM, which controls_init rejects rather than clamps"
    (is (some? (matrix/resolution-problem {:w (inc matrix/max-dim) :h 480}))))
  (testing "non-integer dimensions"
    (is (some? (matrix/resolution-problem {:w 800.5 :h 480}))))
  (testing "every problem is reported at once, not just the first"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (matrix/validate-resolutions!
                          [{:w 0 :h 480} {:w 800 :h -1}])))]
      (is (= 2 (count (:problems (ex-data e))))))))

(deftest mirrored-max-dim-still-matches-main-c
  (let [text (slurp (io/file repo-root "renderer" "src" "main.c"))
        m (re-find #"#define\s+CONTROLS_MAX_DIM\s+(\d+)u" text)]
    (is (some? m) "CONTROLS_MAX_DIM not found in renderer/src/main.c")
    (is (= matrix/max-dim (parse-long (second m))))))

(deftest cell-labels-are-filesystem-safe-and-unique
  (let [cells (matrix/expand {})
        labels (map matrix/cell-label cells)]
    (is (= (count cells) (count (set labels))))
    (is (every? #(re-matches #"[a-z0-9x-]+" %) labels))
    (is (not-any? #(str/includes? % "/") labels))))
