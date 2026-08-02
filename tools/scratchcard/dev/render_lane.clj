(ns render-lane
  "The Tier-2 gate: the scratchcard pipeline against the REAL wasm.

  Run it (GraalVM required — devcards.host refuses a stock JDK):

      tools/uber.sh 'cd tools/scratchcard && clojure -M:render-lane'

  Exit codes SEPARATE a verdict from a precondition failure, because a check
  that dies on a broken harness wears the right colour for the wrong reason:

      0  every clause passed
      1  a clause FAILED — a real verdict
      3  CANNOT RUN — the wasm is missing, or the runtime is not optimizing

  WHAT IT PINS, and each clause exists because its absence would be silent:

  - the SHIPPED EXAMPLE RENDERS CLEAN across the whole default matrix. It is
    the file an author copies; if it ships defective, the first thing every new
    user does produces a red report and they learn to ignore reports. This
    clause is a regression guard on a real defect: the example once produced
    154 findings because its root carried no layout.

  - the matrix is EXACTLY the expected size, never merely non-empty. A
    discovery collapse and a clean small run print the same reassuring number.

  - VANILLA and STOCK framebuffers are BIT-IDENTICAL at every canvas and mode.
    Family 1 restates the stock formulas, so any divergence is a theme defect —
    and this is the only check in the tool that can see it.

  - a PLANTED defect is CAUGHT. Without this the clean run above proves only
    that the lanes are quiet, not that they are awake."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [devcards.host :as host]
   [scratchcard.matrix :as matrix]
   [scratchcard.run :as run]
   [scratchcard.scope :as scope]))

(def ^:private workspace
  "The repo root, DERIVED rather than assumed.

  This previously defaulted to `/workspace` — the path `tools/uber.sh` mounts
  the repo at. That is correct in the container and WRONG everywhere else, and
  CI runs this lane on a plain runner where the checkout lives under
  $GITHUB_WORKSPACE. The lane then looked for the wasm in a directory that does
  not exist and exited 3 CANNOT RUN, while the make-level `wasm-present` guard
  — which uses a relative path — had already passed.

  `scope/discover-repo-root` asks git, so it is right in the container, on a
  runner, and in any clone. The env var stays as an explicit override."
  (or (System/getenv "UBER_WORKSPACE")
      (scope/discover-repo-root (System/getProperty "user.dir"))
      "/workspace"))

(defn- fail! [msg] (println "  FAIL:" msg) false)
(defn- pass! [msg] (println "  ok:  " msg) true)

(defn- clause-clean-example
  [result]
  (let [expected (matrix/expected-count {})]
    (and (if (= expected (:cells result))
           (pass! (str "matrix is EXACTLY " expected " cells"))
           (fail! (str "expected " expected " cells, got " (:cells result))))
         (if (zero? (:failed result))
           (pass! "every cell rendered")
           (fail! (str (:failed result) " cell(s) failed to render")))
         (if (get-in result [:findings :clean?])
           (pass! "the shipped example is CLEAN across the default matrix")
           (fail! (str "the shipped example has findings: "
                       (pr-str (get-in result [:findings :by-invariant]))))))))

(defn- read-renders
  [result]
  (-> (io/file (:dir result) "manifest.edn") slurp read-string :renders))

(defn- clause-vanilla-equals-stock
  [result]
  (let [by-cell (into {} (map (juxt :cell :fb-sha256)) (read-renders result))
        pairs (for [[cell sha] by-cell
                    :when (str/starts-with? cell "vanilla-")]
                [cell sha (get by-cell (str/replace-first cell "vanilla-" "stock-"))])
        bad (remove (fn [[_ v s]] (and v s (= v s))) pairs)]
    (cond
      (empty? pairs) (fail! "no vanilla cells found — the comparison judged nothing")
      (seq bad) (fail! (str "vanilla != stock in " (count bad) " cell(s): "
                            (str/join ", " (map first bad))))
      :else (pass! (str "vanilla == stock, bit-identical, in all "
                        (count pairs) " cells")))))

(defn- clause-planted-defect-is-caught
  []
  ;; A clean run proves the lanes are QUIET. This proves they are AWAKE.
  (let [f (io/file (System/getProperty "java.io.tmpdir") "scratchcard-planted.edn")]
    (spit f (pr-str {:type :screen :events {}
                     :subjects {:bp {:type :int} :theme_dark {:type :int}}
                     ;; No layout on the root — the exact defect the example
                     ;; was repaired for.
                     :tree {:tag :lv_obj
                            :children [{:tag :lv_label :class "font-font-heading text-fg-0"
                                        :text "a deliberately unlaid-out screen with a long line"}
                                       {:tag :lv_slider :class "w-120"
                                        :slider_props {:min_value 0 :max_value 100 :value 65}}]}}))
    (let [r (run/regenerate! {:repo-root workspace :screen-path (str f)
                              :card "planted-defect"
                              :matrix-opts {:resolutions [{:w 800 :h 480}]}})]
      ;; THE RUN MUST HAVE SUCCEEDED FIRST. `regenerate!`'s failure return
      ;; carries NO `:findings` key, so `(get-in r [:findings :clean?])` is nil
      ;; — falsey — and a screen that failed to BUILD would have printed
      ;; "planted defect caught: nil" and passed. That is a precondition
      ;; failure wearing a green, which is the one thing this clause exists to
      ;; make impossible.
      (cond
        (not (:ok r))
        (fail! (str "the planted screen did not render at all (" (:error r)
                    ") — this clause proves nothing about the lanes"))

        (get-in r [:findings :clean?])
        (fail! "a screen with no root layout reported CLEAN — the lanes are asleep")

        :else
        (pass! (str "planted defect caught: "
                    (pr-str (get-in r [:findings :by-invariant]))))))))

(defn -main
  [& _]
  (when-not (.isFile (io/file workspace "renderer/output/controls.wasm"))
    (println "CANNOT RUN: renderer/output/controls.wasm is missing —"
             "build it with `make -f renderer.mk wasm`")
    (System/exit 3))
  ;; A NON-OPTIMIZING RUNTIME IS A PRECONDITION FAILURE, NOT A VERDICT. The
  ;; docstring above has always promised exit 3 for it; without this probe a
  ;; stock JDK instead failed every cell and exited 1, so a missing GraalVM
  ;; read as "the shipped example is defective" — a broken harness wearing a
  ;; verdict's colour, which is the exact confusion the exit codes exist to
  ;; prevent. Probed with the cheapest possible context.
  (try
    (host/close! (host/start! {:wasm (str workspace "/renderer/output/controls.wasm")
                               :assets (str workspace "/renderer/assets")
                               :w 8 :h 8}))
    (catch Exception e
      (println "CANNOT RUN:" (ex-message e))
      (System/exit 3)))
  (println "render-lane: scratchcard against the real wasm")
  (let [result (run/regenerate!
                {:repo-root workspace
                 :screen-path (str workspace "/tools/scratchcard/example/hello.edn")
                 :card "render-lane-example"})
        ;; EVALUATED, not short-circuited. `and` would skip every later clause
        ;; on the first failure — including the awake-check, which is the
        ;; diagnosis you most want when the example has suddenly grown
        ;; findings. Collect all the booleans, then reduce.
        results [(clause-clean-example result)
                 (clause-vanilla-equals-stock result)
                 (clause-planted-defect-is-caught)]
        ok (every? true? results)]
    (println (if ok "render-lane: GREEN" "render-lane: RED"))
    (shutdown-agents)
    (System/exit (if ok 0 1))))
