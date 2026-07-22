(ns asgard.proto.cmd
  "Pronto mapper for command protobuf ↔ EDN conversion.
   Proto Java classes must be compiled and on classpath BEFORE this ns loads."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [pronto.core :as pronto]
            [pronto.utils :as pronto-utils])
  (:import [cmd JonSharedCmd$Root]
           [com.google.protobuf Descriptors$Descriptor Descriptors$EnumDescriptor
            Descriptors$EnumValueDescriptor Descriptors$FieldDescriptor
            Descriptors$FieldDescriptor$JavaType]))

(set! *warn-on-reflection* true)

(pronto/defmapper cmd-mapper [JonSharedCmd$Root] :key-name-fn pronto-utils/->kebab-case)

(defn- coerce-enum-numbers
  "Walk a command map against proto message descriptor `desc`, converting any ENUM
   field whose value is a NUMBER into the pronto enum keyword (the value's name).
   Recurses into message fields. Application requests and native controls may
   identify enum fields by number, but pronto's clj-map->proto-map wants the
   keyword — so this is the single coercion point. Non-enum values and already-keyword
   enums pass through untouched (internal keyword callers keep working). A number
   with no matching enum value passes through so pronto raises the type error."
  [^Descriptors$Descriptor desc m]
  (reduce-kv
   (fn [acc k v]
     (let [field (Descriptors$Descriptor/.findFieldByName desc
                                                          (str/replace (name k) "-" "_"))]
       (assoc acc
              k
              (cond (nil? field) v
                    (and (number? v)
                         (= Descriptors$FieldDescriptor$JavaType/ENUM
                            (Descriptors$FieldDescriptor/.getJavaType field)))
                    (if-let [evd (Descriptors$EnumDescriptor/.findValueByNumber
                                  (Descriptors$FieldDescriptor/.getEnumType field)
                                  (int v))]
                      (keyword (Descriptors$EnumValueDescriptor/.getName evd))
                      v)
                    (and (map? v)
                         (= Descriptors$FieldDescriptor$JavaType/MESSAGE
                            (Descriptors$FieldDescriptor/.getJavaType field)))
                    (coerce-enum-numbers (Descriptors$FieldDescriptor/.getMessageType field)
                                         v)
                    :else v))))
   {}
   m))
(m/=> coerce-enum-numbers
      [:=> [:cat some? [:map-of :keyword :any]] [:map-of :keyword :any]])

(defn wrap-in-root
  "Wrap a subsystem command map in the top-level Root proto message.
   subsystem: kebab-case string matching proto field name (e.g., \"compass\")
   cmd-map: EDN subsystem Root map (e.g., {:set-magnetic-declination {:value 15.0}})
   Enum fields may be given as their number or keyword —
   coerce-enum-numbers normalizes numbers to keywords against the proto descriptor.
   Returns binary protobuf of cmd.JonSharedCmd$Root ready for cmd_server."
  ^bytes [^String subsystem cmd-map]
  (let [root-map (coerce-enum-numbers
                  (JonSharedCmd$Root/getDescriptor)
                  (assoc {(keyword subsystem) cmd-map}
                         :protocol-version 1
                         :client-type :JON_GUI_DATA_CLIENT_TYPE_LOCAL_NETWORK
                         :client-app :JON_GUI_DATA_CLIENT_APP_DESKTOP_NATIVE))]
    (pronto/proto-map->bytes
     (pronto/clj-map->proto-map cmd-mapper cmd.JonSharedCmd$Root root-map))))
(m/=> wrap-in-root [:=> [:cat [:string {:min 1}] [:map-of :keyword :any]] bytes?])

(defn build-ping
  "Build a top-level Root proto with Ping payload (oneof field 28).
   Used for cmd_server keepalive. Includes required validation fields."
  ^bytes []
  (pronto/proto-map->bytes (pronto/clj-map->proto-map
                            cmd-mapper
                            cmd.JonSharedCmd$Root
                            {:ping {}
                             :protocol-version 1
                             :client-type :JON_GUI_DATA_CLIENT_TYPE_LOCAL_NETWORK
                             :client-app :JON_GUI_DATA_CLIENT_APP_DESKTOP_NATIVE})))
(m/=> build-ping [:=> [:cat] bytes?])