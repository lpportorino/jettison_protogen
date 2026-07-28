(ns lvgl-codegen.resolve
  "Token resolution: semantic → primitive → concrete LVGL values."
  (:require [malli.core :as m]))

(set! *warn-on-reflection* true)

;; -- Malli function schemas for instrumentation --
(def ^:private tokens-map
  "The tokens EDN map: keyword category keys (:colors :semantic :spacing …)
   to their category maps."
  [:map-of :keyword some?])

(def ^:private semantic-key keyword?)

(defn- primitive!
  "The second (semantic -> primitive) hop must also resolve — a nil here
   would ride to pronto as a nil prop value with zero EDN context."
  [value sem-key prim-key kind]
  (when (nil? value)
    (throw (ex-info (str "Semantic token " (name sem-key)
                         " references unknown " kind
                         " primitive " (pr-str prim-key))
                    {:semantic sem-key :primitive prim-key :kind kind})))
  value)

(defn resolve-color
  "Resolve a color token reference to hex string(s): the semantic layer
   first ({:dark/:light} themed map or a primitive alias), else a direct
   primitive :colors entry (the :style surface references primitives like
   :violet without forcing a semantic alias for every one-off).
   Returns {:dark \"#hex\" :light \"#hex\"} for themed tokens, or a bare
   hex string. Throws on a ref in neither layer."
  [tokens sem-key]
  (let [semantic (get-in tokens [:semantic sem-key])
        colors (:colors tokens)]
    (when-not (or semantic (get colors sem-key))
      (throw (ex-info (str "Unknown color token: "
                           (name sem-key)
                           " — neither a :semantic nor a :colors entry")
                      {:token sem-key})))
    (cond (map? semantic)
          {:dark (primitive! (get colors (:dark semantic)) sem-key (:dark semantic) "color")
           :light
           (primitive! (get colors (:light semantic)) sem-key (:light semantic) "color")}
          (keyword? semantic) (primitive! (get colors semantic) sem-key semantic "color")
          :else (get colors sem-key))))

(defn resolve-font
  "Resolve a semantic font token to LVGL C font symbol name.
   :font-body → :b612mono-bold-16 → \"b612mono_bold_16\""
  [tokens sem-key]
  (let [font-key (get-in tokens [:semantic sem-key])]
    (when-not font-key
      (throw (ex-info (str "Unknown font token: " (name sem-key)) {:token sem-key})))
    (let [font (primitive! (get-in tokens [:fonts font-key]) sem-key font-key "font")]
      (str (:family font) "_" (:size font)))))

(defn resolve-spacing
  "Resolve a semantic spacing token to pixel integer.
   :spacing-md → 3 → 8"
  [tokens sem-key]
  (let [scale-key (get-in tokens [:semantic sem-key])]
    (when-not scale-key
      (throw (ex-info (str "Unknown spacing token: " (name sem-key)) {:token sem-key})))
    (primitive! (get (:spacing tokens) scale-key) sem-key scale-key "spacing")))

(defn resolve-radius
  "Resolve a semantic radius token to pixel integer.
   :radius-card → :lg → 4"
  [tokens sem-key]
  (let [r-key (get-in tokens [:semantic sem-key])]
    (when-not r-key
      (throw (ex-info (str "Unknown radius token: " (name sem-key)) {:token sem-key})))
    (primitive! (get (:radii tokens) r-key) sem-key r-key "radius")))

(defn resolve-shadow
  "Resolve a semantic shadow token.
   Returns {:dark shadow-map :light shadow-map} for themed,
   or a bare shadow-map for invariant."
  [tokens sem-key]
  (let [s-ref (get-in tokens [:semantic sem-key])
        lookup (fn [k] (get (:shadows tokens) k))]
    (when-not s-ref
      (throw (ex-info (str "Unknown shadow token: " (name sem-key)) {:token sem-key})))
    (if (map? s-ref)
      {:dark (lookup (:dark s-ref)) :light (lookup (:light s-ref))}
      (lookup s-ref))))

(defn resolve-opacity
  "Resolve a semantic opacity token to integer (0-255)."
  [tokens sem-key]
  (let [o-ref (get-in tokens [:semantic sem-key])]
    (when-not o-ref
      (throw (ex-info (str "Unknown opacity token: " (name sem-key)) {:token sem-key})))
    (if (map? o-ref)
      {:dark (get (:opacity tokens) (:dark o-ref))
       :light (get (:opacity tokens) (:light o-ref))}
      (get (:opacity tokens) o-ref))))

(defn resolve-border-width
  "Resolve a semantic border-width token to pixel integer."
  [tokens sem-key]
  (let [bw-key (get-in tokens [:semantic sem-key])]
    (when-not bw-key
      (throw (ex-info (str "Unknown border-width token: " (name sem-key))
                      {:token sem-key})))
    (get (:border-widths tokens) bw-key)))

;; -- Function schema registrations --
(m/=> resolve-color
      [:=> [:cat tokens-map semantic-key]
       [:maybe [:or [:map [:dark string?] [:light string?]] [:string {:min 1}]]]])

(m/=> resolve-font [:=> [:cat tokens-map semantic-key] [:maybe string?]])

(m/=> resolve-spacing [:=> [:cat tokens-map semantic-key] [:maybe int?]])

(m/=> resolve-radius [:=> [:cat tokens-map semantic-key] [:maybe int?]])

(m/=> resolve-shadow [:=> [:cat tokens-map semantic-key] :any])

(m/=> primitive!
      [:=>
       [:cat [:maybe [:or int? [:string {:min 1}] [:map-of :keyword some?]]] keyword?
        [:or keyword? int?] [:string {:min 1}]] some?])

(m/=> resolve-opacity [:=> [:cat tokens-map semantic-key] :any])

(m/=> resolve-border-width [:=> [:cat tokens-map semantic-key] [:maybe int?]])