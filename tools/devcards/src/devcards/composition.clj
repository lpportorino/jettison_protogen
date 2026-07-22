(ns devcards.composition
  "The authored-composition corpus lane — the public-lego companion to
   the atomic corpus (corpus/composition.edn beside corpus/spec.edn).

   Cards are DATA: each names a devcards.legos maker + its options + an
   absolute placement; `build-all` materializes the node (lego fn -> node
   map, declared :transform, then :placement as :x/:y) and compiles it
   through devcards.fixtures/build-authored-card — the ONE gate into
   events / absolute position / part-selector styling. A composition
   card proves the PUBLIC LEGO contract (geometry, palette, event
   identities), never a theme surface; the atomic lane never admits
   these cards, and the authored lane never admits an atomic card's
   implicit-theme claim.

   The runner (devcards.core) renders the built cards through the same
   pixel/invariant/golden machinery as the atomic corpus, adds the
   :inert-prop pin (devcards.gates/inert-prop-findings) + the
   vanilla≡stock family pin, and drives the interaction lane
   (devcards.interaction)."
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk]
            [devcards.fixtures :as fixtures]
            [devcards.legos :as legos]))

(set! *warn-on-reflection* true)

(def inventory-path
  "The composition-lane card inventory (tool-relative)."
  "corpus/composition.edn")

(def ^:private inventory-keys #{:builder :canvas :cards :lane})

(def ^:private card-keys
  #{:base-card :expect :id :lego :notes :opts :placement :transform})

(def ^:private placement-keys #{:x :y})

(def ^:private makers
  "Lego keyword -> maker fn. Closed: a new lego is a deliberate corpus +
   devcards.legos addition, never an open dispatch."
  {:scrubber legos/scrubber :dock-panel legos/dock-panel})

(defn- strip-seek-on-press
  "The :strip-seek-on-press corpus transform — dissoc the prop wherever a
   map carries it. The scrubber lego sets `seek_on_press`
   UNCONDITIONALLY (press-seek is the scrubber contract), so the
   prop-absent shape exists only as this declared corpus surgery, for
   the :inert-prop pixel-inertness pin."
  [node]
  (walk/postwalk
   #(if (and (map? %) (contains? % :seek_on_press)) (dissoc % :seek_on_press) %)
   node))

(def ^:private transforms {:strip-seek-on-press strip-seek-on-press})

(defn- assert-closed
  "Throw unless every key of `m` is in `allowed` — the same closed-shape
   law the fixtures builder enforces, applied to the inventory data."
  [ctx allowed m]
  (when-not (map? m)
    (throw (ex-info (str ctx ": expected a map") {:ctx ctx :got m})))
  (let [extra (remove allowed (keys m))]
    (when (seq extra)
      (throw (ex-info (str ctx ": unknown keys (closed shape)")
                      {:ctx ctx :unknown (vec extra) :allowed (vec (sort allowed))})))))

(defn- validate-card!
  "One inventory card's closed shape + cross-card claims (`ids` = every
   card id, for :base-card resolution)."
  [ids {:keys [id lego opts placement expect base-card transform] :as card}]
  (assert-closed (str "composition card " id) card-keys card)
  (when-not (and (string? id) (seq ^String id))
    (throw (ex-info "composition card requires a non-empty string :id" {:card card})))
  (when-not (contains? makers lego)
    (throw (ex-info (str "composition card " id ": unknown :lego")
                    {:lego lego :known (vec (sort (keys makers)))})))
  (when-not (map? opts)
    (throw (ex-info (str "composition card " id ": :opts must be a map") {:opts opts})))
  (assert-closed (str "composition card " id " :placement") placement-keys placement)
  (when-not (and (int? (:x placement)) (int? (:y placement)))
    (throw (ex-info (str "composition card " id ": :placement needs int :x/:y")
                    {:placement placement})))
  (when-not (contains? #{:composition :inert-prop} expect)
    (throw (ex-info (str "composition card " id ": unknown :expect") {:expect expect})))
  (when (some? transform)
    (when-not (contains? transforms transform)
      (throw (ex-info (str "composition card " id ": unknown :transform")
                      {:transform transform :known (vec (sort (keys transforms)))}))))
  (when (= :inert-prop expect)
    (when-not (and (string? base-card) (not= base-card id) (some #(= base-card %) ids))
      (throw (ex-info (str "composition card " id
                           ": :inert-prop requires :base-card naming a sibling")
                      {:base-card base-card :ids (vec ids)})))
    (when-not transform
      (throw (ex-info (str "composition card " id
                           ": :inert-prop requires a declared :transform")
                      {:card card})))))

(defn load-inventory
  "Read + hand-validate `corpus/composition.edn`: closed shapes
   throughout, known lego makers and transforms, distinct ids, and every
   :inert-prop card naming a :base-card sibling + a declared :transform.
   Returns the parsed inventory."
  []
  (let [inv (edn/read-string (slurp inventory-path))]
    (assert-closed "composition inventory" inventory-keys inv)
    (when-not (= :authored-composition (:lane inv))
      (throw (ex-info "composition inventory :lane mismatch" {:lane (:lane inv)})))
    (when-not (and (pos? (long (get-in inv [:canvas :w] 0)))
                   (pos? (long (get-in inv [:canvas :h] 0))))
      (throw (ex-info "composition inventory :canvas missing" {:canvas (:canvas inv)})))
    (let [cards (:cards inv)]
      (when-not (and (vector? cards) (seq cards))
        (throw (ex-info "composition inventory needs a non-empty :cards vector" {})))
      (let [ids (mapv :id cards)]
        (when-not (= (count ids) (count (set ids)))
          (throw (ex-info "composition card ids must be distinct" {:ids ids})))
        (doseq [card cards] (validate-card! ids card))))
    inv))

(defn materialize
  "One inventory card -> its authored node map: the lego maker over
   :opts, the declared :transform (when present), then :placement as
   absolute :x/:y. The placement positions the lego's OUTER node — for
   the scrubber that is the hit-halo wrapper, so the TRACK lands at
   placement + devcards.legos/scrubber-halo."
  [{:keys [lego opts placement transform]}]
  (cond-> ((get makers lego) opts)
    transform ((get transforms transform))
    true (assoc :x (:x placement) :y (:y placement))))

(defn build-all
  "Build the whole composition corpus to Screen bytes through the
   authored lane, in inventory order (deterministic output order is part
   of the contract, matching the atomic build). Returns a vector of
   {:id :expect :base-card :lego :bytes}."
  ([] (build-all (load-inventory)))
  ([inventory]
   (let [canvas (:canvas inventory)]
     (mapv (fn [{:keys [id expect base-card lego] :as card}]
             {:id id
              :expect expect
              :base-card base-card
              :lego lego
              :bytes (fixtures/build-authored-card canvas
                                                   {:id id :node (materialize card)})})
           (:cards inventory)))))
