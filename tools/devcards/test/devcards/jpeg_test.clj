(ns devcards.jpeg-test
  "Unit tests for the pure image helpers in `devcards.jpeg` — currently the
   `content-bbox` dump-tree reduction (the crop-rect strategy for whole-screen
   consumers). `crop` itself stays proven end-to-end by the gallery freshness
   gate (git diff over the committed doc tree), which exercises it on every
   committed card."
  (:require [clojure.test :refer [deftest is testing]]
            [devcards.jpeg :as jpeg]))

(def ^:private root-coords
  "Full-canvas root rect (inclusive dump coords)."
  [0 0 1919 1079])

(defn- screen
  "A dump-tree-shaped root (the shape `controls_dump_tree` emits, parsed with
   keyword keys) holding `children`."
  [& children]
  {:type "lv_obj" :coords root-coords :bp 0 :theme_dark 1
   :children (vec children)})

(deftest union-of-visible-children
  (testing "the bbox is the union of every drawn node's coords, root excluded"
    (is (= [16 16 680 260]
           (jpeg/content-bbox
            (screen {:type "lv_obj" :coords [16 16 336 196] :children []}
                    {:type "lv_label" :coords [360 80 680 260] :children []}))))))

(deftest descendants-count
  (testing "a grandchild outside its parent's rect still widens the union"
    (is (= [50 50 300 300]
           (jpeg/content-bbox
            (screen {:type "lv_obj" :coords [100 100 300 300]
                     :children [{:type "lv_label" :coords [50 50 90 90]
                                 :children []}]}))))))

(deftest hidden-node-excluded
  (testing "a HIDDEN node contributes nothing"
    (is (= [10 10 40 40]
           (jpeg/content-bbox
            (screen {:type "lv_obj" :coords [10 10 40 40] :children []}
                    {:type "lv_obj" :coords root-coords :hidden true
                     :children []}))))))

(deftest hidden-subtree-pruned
  (testing "descendants of a hidden node are pruned even though the dump does
            not re-flag them (LV_OBJ_FLAG_HIDDEN is per-obj in the dump)"
    (is (= [10 10 40 40]
           (jpeg/content-bbox
            (screen {:type "lv_obj" :coords [10 10 40 40] :children []}
                    {:type "lv_obj" :coords [500 500 600 600] :hidden true
                     :children [{:type "lv_label" :coords root-coords
                                 :children []}]}))))))

(deftest fully-clipped-node-excluded
  (testing "vis_px 0 (fully ancestor-clipped, never drawn) contributes nothing;
            a partially visible node still counts in full"
    (is (= [10 10 40 40]
           (jpeg/content-bbox
            (screen {:type "lv_obj" :coords [10 10 40 40] :children []}
                    {:type "lv_obj" :coords [900 900 1100 1000] :vis_px 0
                     :children []}))))
    (is (= [10 10 1100 1000]
           (jpeg/content-bbox
            (screen {:type "lv_obj" :coords [10 10 40 40] :children []}
                    {:type "lv_obj" :coords [900 900 1100 1000] :vis_px 5
                     :children []}))))))

(deftest fully-clipped-node-keeps-drawable-children
  (testing "vis_px 0 removal is PER-NODE, never a subtree prune: each node's
            vis_px is computed independently, and a child can outdraw its
            clipped parent via OVERFLOW_VISIBLE ext-draw growth"
    (is (= [200 200 260 260]
           (jpeg/content-bbox
            (screen {:type "lv_obj" :coords [900 900 1100 1000] :vis_px 0
                     :children [{:type "lv_label" :coords [200 200 260 260]
                                 :children []}]}))))))

(deftest degenerate-area-excluded
  (testing "an inverted (empty) rect contributes nothing; a 1px rect counts"
    (is (= [7 7 7 7]
           (jpeg/content-bbox
            (screen {:type "lv_obj" :coords [7 7 7 7] :children []}
                    {:type "lv_obj" :coords [10 10 9 9] :children []}))))))

(deftest empty-screen-yields-nil
  (testing "no drawable non-root node → nil (caller decides the fallback)"
    (is (nil? (jpeg/content-bbox (screen))))
    (is (nil? (jpeg/content-bbox
               (screen {:type "lv_obj" :coords root-coords :hidden true
                        :children []}))))))

(deftest canvas-rect-node-carries-no-crop-information
  (testing "a node spanning EXACTLY the root rect (a screen-background
            container) is excluded from the union — it always says
            'everything', which crops nothing; the real content underneath
            decides the rect"
    (is (= [10 10 230 270]
           (jpeg/content-bbox
            (screen {:type "lv_obj" :coords root-coords
                     :children [{:type "lv_obj" :coords [10 10 230 270]
                                 :children []}]})))))
  (testing "a screen whose ONLY drawable spans the canvas yields nil — the
            caller's full-canvas fallback produces the identical image"
    (is (nil? (jpeg/content-bbox
               (screen {:type "lv_obj" :coords root-coords :children []})))))
  (testing "a nearly-canvas rect (not exact) still counts — only the exact
            no-information rect is excluded"
    (is (= [0 0 1919 1078]
           (jpeg/content-bbox
            (screen {:type "lv_obj" :coords [0 0 1919 1078]
                     :children []}))))))
