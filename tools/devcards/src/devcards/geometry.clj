(ns devcards.geometry
  "Pure box arithmetic over dump-tree coords — the shared primitive under
   every geometry rule (overlap, touch-proximity, the layer contract's
   z-inversion clause).

   A box is a dump `:coords` vector [x1 y1 x2 y2], INCLUSIVE on both ends
   (lv_obj_get_coords fills an lv_area_t, whose x2/y2 are the last pixel
   INSIDE the box, not one past it). Every function here is off-by-one
   sensitive to that: a 10px-wide box at the origin is [0 0 9 9], and the
   box starting at x=10 TOUCHES it with zero pixels between.

   Exact integer arithmetic, no tolerance and no noise floor — occlusion
   and adjacency are decidable from the rects alone. That is a deliberate
   boundary: the contrast lane's three-way uncertain band exists because
   its measurement has a noise floor wider than its separating gap, and
   importing that band here would manufacture uncertainty this arithmetic
   does not have."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- valid-box?
  [box]
  (and (vector? box) (= 4 (count box)) (every? int? box)))

(defn check-box!
  "Throw unless `box` is a 4-int coord vector. A node whose :coords are
   absent or malformed must never silently compare as 'far apart' — that
   is the failure mode where a broken run and a clean run produce
   byte-identical output."
  [box where]
  (when-not (valid-box? box)
    (throw (ex-info (str "malformed coords in " where
                         " — expected [x1 y1 x2 y2] of ints")
                    {:coords box :where where})))
  box)

(defn separation
  "Pixels of clear space between boxes `a` and `b`, as an integer:
   0 = they touch with nothing between, negative = they overlap (the
   magnitude is the shallower axis' penetration depth), positive = that
   many fully-clear pixel columns/rows separate them.

   Taking the MAX of the two axes is what makes this a separation rather
   than a distance: two boxes are apart when a gap exists on EITHER axis,
   so the axis with the larger clearance is the one that separates them."
  [a b]
  (check-box! a "separation/a")
  (check-box! b "separation/b")
  (let [[ax1 ay1 ax2 ay2] a
        [bx1 by1 bx2 by2] b
        dx (max (- bx1 ax2 1) (- ax1 bx2 1))
        dy (max (- by1 ay2 1) (- ay1 by2 1))]
    (max dx dy)))

(defn intersect?
  "Do the two boxes share at least one pixel?"
  [a b]
  (neg? (separation a b)))

(defn intersection
  "The box of pixels `a` and `b` share, or **nil** when they share none.

   nil is the explicit empty result, deliberately not a degenerate box: on
   INCLUSIVE coords an empty region cannot be spelled as a rect at all
   (x1 > x2 is not 'zero wide', it is inverted), and every function here
   would happily do arithmetic on the inverted vector and return a
   confident wrong number. Returning nil forces the caller to say what an
   empty intersection MEANS in its rule rather than letting it flow on as
   a box."
  [a b]
  (check-box! a "intersection/a")
  (check-box! b "intersection/b")
  (let [[ax1 ay1 ax2 ay2] a
        [bx1 by1 bx2 by2] b
        x1 (max ax1 bx1)
        y1 (max ay1 by1)
        x2 (min ax2 bx2)
        y2 (min ay2 by2)]
    (when (and (<= x1 x2) (<= y1 y2))
      [x1 y1 x2 y2])))

(defn too-close?
  "Are `a` and `b` closer than `gap-px` clear pixels? `gap-px` 0 is the
   strict-overlap rule (only a shared pixel fires); 1 additionally
   catches boxes that touch; N demands N clear pixels between them.

   One knob spans both the overlap rule and the layer contract's 'no two
   elements touch within a threshold' clause, so raising the strictness
   is a data change, never a code change."
  [a b gap-px]
  (< (separation a b) gap-px))

(defn describe
  "Human-readable box for a finding :detail."
  ^String [box]
  (if (valid-box? box)
    (let [[x1 y1 x2 y2] box]
      (str "[" (str/join "," [x1 y1 x2 y2]) "] "
           (inc (- x2 x1)) "x" (inc (- y2 y1))))
    (pr-str box)))
