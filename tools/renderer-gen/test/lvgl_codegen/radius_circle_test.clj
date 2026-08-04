(ns lvgl-codegen.radius-circle-test
  "Pins the `:radius-circle` design token to LVGL's own `LV_RADIUS_CIRCLE`.

  WHY A TOKEN NEEDS A TEST AT ALL, when no other radius token has one. The rest
  of `:radii` are LENGTHS — `:lg` is 4px because somebody chose 4px, and choosing
  5px tomorrow is a design decision, not a defect. `:circle` is not a length: it
  is a SENTINEL the draw path tests for by equality (`lv_draw_rect.h`), so it is
  correct at exactly one value and wrong at every other. A token that merely
  looked like a big radius would round to half the shorter side instead of a
  pill, which is a difference of a few pixels on a small indicator — visible in a
  render, invisible in a review.

  READS THE VENDORED HEADER rather than restating the number, because restating
  it is the defect this token exists to remove: an authoring surface that
  hand-codes 32767 and a token that hand-codes 32767 are the same literal in two
  places. The header is the one home, and an LVGL pin bump that renumbers or
  renames the define reds this test instead of silently un-rounding an indicator.

  WHAT IT DOES NOT COVER. It asserts the token RESOLVES to the sentinel, never
  that any particular widget uses it, and never that the renderer draws a circle
  — the framebuffer goldens own that."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [lvgl-codegen.resolve :as resolve]))

(set! *warn-on-reflection* true)

(def ^:private draw-rect-header
  "The LVGL header declaring the circle sentinel, repo-root-relative from the
  tool root the suite runs in."
  "../../renderer/lvgl/src/draw/lv_draw_rect.h")

(defn- lv-radius-circle
  "LVGL's own `LV_RADIUS_CIRCLE`, parsed from the vendored header.

  Throws rather than returning nil on a missing file or an unmatched define: a
  nil would make every assertion below compare against nothing and pass for the
  wrong reason, which is the failure mode a sentinel test can least afford."
  []
  (let [f (io/file draw-rect-header)]
    (when-not (.isFile f)
      (throw (ex-info "lv_draw_rect.h not found — the vendored LVGL tree moved"
                      {:path draw-rect-header})))
    (let [m (re-find #"(?m)^#define\s+LV_RADIUS_CIRCLE\s+(0[xX][0-9a-fA-F]+|\d+)"
                     (slurp f))]
      (when-not m
        (throw (ex-info (str "lv_draw_rect.h declares no LV_RADIUS_CIRCLE — the "
                             "sentinel was renamed or removed across an LVGL major")
                        {:path draw-rect-header})))
      (Long/decode ^String (second m)))))

(defn- tokens [] (edn/read-string (slurp "edn/tokens.edn")))

(deftest radius-circle-resolves-to-the-lvgl-sentinel
  (testing "the named token IS the constant the draw path compares against"
    (is (= (lv-radius-circle) (resolve/resolve-radius (tokens) :radius-circle)))))

(deftest the-large-radius-is-not-the-sentinel
  (testing ":full is an ordinary large radius LVGL CLAMPS, not the sentinel"
    ;; Stated as an assertion because the two read alike in the token file and
    ;; collapsing them would be a plausible tidy-up that silently changes how a
    ;; non-square box rounds.
    (is (not= (lv-radius-circle) (get-in (tokens) [:radii :full])))))
