(ns devcards.corpus
  "Generic themed-corpus DRIVER — the render loop, error partitioning, and
   golden-set diffing every bring-your-own-corpus consumer plugs into,
   promoted from the private screen-corpus driver so a consumer authors
   corpus CONFIG (its screens, variants, and render fn), not corpus
   infrastructure. Pairs with `devcards.golden` (manifest IO + hashing),
   `devcards.probe`/`devcards.pointer` (the E2E half), and `devcards.docgen`
   (the doc half).

   The driver knows nothing about hosts, packages, or themes-as-such: a
   VARIANT is any map with a :key (dark/light, families, breakpoints — the
   consumer decides), a SCREEN any map with an :id, and the render fn owns
   everything in between. HOW-public / WHAT-private: no consumer content
   ever lands here."
  (:require [clojure.set :as set]))

(set! *warn-on-reflection* true)

(defn render-corpus
  "Render every (screen, variant) pair once, in parallel. `screens` = maps
   each carrying :id; `variants` = maps each carrying :key; `result1` =
   (fn [screen variant] -> {:sha256 .. :w .. :h .. :findings [..]}) — a
   throw becomes a tagged {:variant :id :error msg} entry instead of
   aborting the sweep (a corpus reports every failing screen). Returns
   {:by-variant {vkey (sorted-map id {:sha256 :w :h})}
    :findings [finding + :variant + :id ...]
    :errors [{:variant :id :error} ...]}
   — errored pairs are partitioned OUT of the golden maps (only successful
   renders get a golden). Empty screens or variants THROW: a zero-pair
   corpus proves nothing and must never return a vacuous green."
  [screens variants result1]
  (when (empty? screens)
    (throw (ex-info "refusing to drive an EMPTY screen set" {})))
  (when (empty? variants)
    (throw (ex-info "refusing to drive an EMPTY variant set" {})))
  (let [results (doall
                 (pmap (fn [[screen variant]]
                         (let [tag {:variant (:key variant) :id (:id screen)}]
                           (try (merge tag (result1 screen variant))
                                (catch Throwable t
                                  (assoc tag :error (or (ex-message t) (str (class t))))))))
                       (for [variant variants screen screens] [screen variant])))
        {ok true errored false} (group-by #(nil? (:error %)) results)
        by-variant (into {}
                         (map (fn [[vk rs]]
                                [vk (into (sorted-map)
                                          (map (fn [{:keys [id sha256 w h]}]
                                                 [id {:sha256 sha256 :w w :h h}]))
                                          rs)]))
                         (group-by :variant (or ok [])))
        findings (into []
                       (mapcat (fn [{:keys [variant id findings]}]
                                 (map #(assoc % :variant variant :id id) findings)))
                       (or ok []))
        errors (mapv #(select-keys % [:variant :id :error]) (or errored []))]
    {:by-variant by-variant :findings findings :errors errors}))

(defn diff-cards
  "Diff a committed golden card map against a freshly rendered one (both
   id -> {:sha256 ..}). Returns {:mismatched [{:id :expected :actual}]
   :missing [ids committed but not rendered] :unexpected [ids rendered but
   not committed]} — the three drift classes a verify lane fails on."
  [expected actual]
  (let [expected-ids (set (keys expected))
        actual-ids (set (keys actual))]
    {:mismatched (vec (for [id (sort (set/intersection expected-ids actual-ids))
                            :let [e (get-in expected [id :sha256])
                                  a (get-in actual [id :sha256])]
                            :when (not= e a)]
                        {:id id :expected e :actual a}))
     :missing (vec (sort (set/difference expected-ids actual-ids)))
     :unexpected (vec (sort (set/difference actual-ids expected-ids)))}))
