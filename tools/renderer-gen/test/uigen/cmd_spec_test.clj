(ns uigen.cmd-spec-test
  "Drives `uigen.cmd-spec/ndc-y-sense` — the clause that decides which vertical
  plane a pre-encoded command's NDC y leaves are written into, and refuses
  rather than guessing when this repository does not settle it.

  WHY THIS ONE CLAUSE EARNS A CANARY. Its wrong answer is undetectable
  everywhere below it. The two planes are both a `double` in `[-1,1]` called
  \"NDC\" (`docs/INTERFACE-CONTRACTS.md` §4.1), so a template built with the
  wrong sense produces a command that decodes cleanly, sits inside every
  declared range, and reads as a plausible operator request — no gate, no
  oracle and no device response distinguishes it from a correct one. The only
  place the mistake is visible is here, before the bytes exist.

  BOTH DIRECTIONS, AND EACH REFUSAL BY ITS OWN REASON. A clause that answered
  every question with a throw would be exactly as useless as one that answered
  every question with a default, so the passing directions are asserted too —
  including the sharp one: a spec with no y slot must come back UNSPECIFIED
  rather than being asked for a plane it has none of. That case is every
  widget-value, by-value and form template this repository ships, so a
  clause that demanded a plane unconditionally would refuse all of them.

  HERMETIC: `ndc-y-sense` takes its patch-fields as an argument, so every case
  is a fixture. The one live-classpath fact asserted is that the command-ids in
  the shipped table are the ones the fixtures name — a green over a table
  nobody uses would prove nothing."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [uigen.cmd-spec :as cs]))

(def ^:private sense
  "The private clause under test."
  #'cs/ndc-y-sense)

(def ^:private y-slot
  "A patch field that receives a y — the condition that obliges a plane."
  {:name "y1" :kind :PATCH_KIND_NDC_Y})

(def ^:private y2-slot
  {:name "y2" :kind :PATCH_KIND_NDC_Y2})

(def ^:private x-slot
  "A patch field that receives an x. The planes differ only vertically, so an
  x-only spec has no plane to state."
  {:name "x1" :kind :PATCH_KIND_NDC_X})

(defn- refusal
  "The ex-data of `ndc-y-sense`'s refusal for `command-id`, or nil if it did not
  refuse. Returns the DATA rather than a boolean so a case can assert WHICH
  command was refused and with what verdict — a bare `thrown?` accepts a
  refusal that fired for a neighbouring reason."
  [command-id patch-fields]
  (try
    (sense command-id patch-fields)
    nil
    (catch clojure.lang.ExceptionInfo e
      (assoc (ex-data e) :message (ex-message e)))))

(deftest a-spec-with-no-y-slot-states-no-plane
  (testing "no patch fields at all — a FIXED template"
    (is (= :NDC_Y_SENSE_UNSPECIFIED (sense "cmd.Lrf.Measure" []))))
  (testing "an x slot only — the planes differ only vertically"
    (is (= :NDC_Y_SENSE_UNSPECIFIED (sense "cmd.Lrf.Measure" [x-slot]))))
  (testing "a value slot only — every form and by-value template"
    (is (= :NDC_Y_SENSE_UNSPECIFIED
           (sense "cmd.Power.SetAlertThreshold"
                  [{:name "value" :kind :PATCH_KIND_SUBJECT_VALUE}])))))

(deftest a-settled-command-answers-its-own-plane
  (testing "the rotary NDC commands are the pointer plane's own"
    (is (= :NDC_Y_SENSE_UP
           (sense "cmd.RotaryPlatform.RotateToNDC" [y-slot])))
    (is (= :NDC_Y_SENSE_UP
           (sense "cmd.RotaryPlatform.HaltWithNDC" [y-slot]))))
  (testing "an ROI rectangle is read in the ser.JonGuiDataROI plane"
    (is (= :NDC_Y_SENSE_DOWN
           (sense "cmd.DayCamera.FocusROI" [y-slot y2-slot])))
    (is (= :NDC_Y_SENSE_DOWN
           (sense "cmd.HeatCamera.ZoomROI" [y-slot y2-slot]))))
  (testing "the second-corner kind alone is enough to oblige a plane"
    (is (= :NDC_Y_SENSE_DOWN
           (sense "cmd.DayCamera.TrackROI" [y2-slot])))))

(deftest an-unresolved-plane-is-refused-and-says-which
  (let [d (refusal "cmd.CV.StartTrackNDC" [y-slot])]
    (is (some? d) "a command whose plane this repo does not settle must refuse")
    (is (= "cmd.CV.StartTrackNDC" (:command-id d)))
    (is (str/includes? (:message d) "UNRESOLVED")
        "the refusal must say the plane was LOOKED AT and not decided — an
         unlisted command is a different fact and gets a different sentence")))

(deftest an-unlisted-command-is-refused-and-says-so-differently
  (let [d (refusal "cmd.Probe.SomeFutureNDCCommand" [y-slot])]
    (is (some? d) "a command absent from the table must refuse")
    (is (str/includes? (:message d) "not in uigen.cmd-spec/ndc-y-plane")
        "absent means nobody has looked; the two verdicts must not be spelled
         the same, or the next reader cannot tell a gap from a decision")))

(deftest the-table-covers-the-commands-this-repo-actually-pre-encodes
  (testing "every settled entry answers with a plane, not a throw"
    ;; The live-classpath half: the fixtures above name ids out of the shipped
    ;; table, so this fails if an id is renamed out from under them.
    (doseq [cid ["cmd.RotaryPlatform.RotateToNDC" "cmd.RotaryPlatform.HaltWithNDC"
                 "cmd.DayCamera.FocusROI" "cmd.DayCamera.TrackROI"
                 "cmd.DayCamera.ZoomROI" "cmd.DayCamera.FxROI"
                 "cmd.HeatCamera.FocusROI" "cmd.HeatCamera.TrackROI"
                 "cmd.HeatCamera.ZoomROI" "cmd.HeatCamera.FxROI"]]
      (is (contains? #{:NDC_Y_SENSE_UP :NDC_Y_SENSE_DOWN} (sense cid [y-slot]))
          (str cid " must answer with a plane")))))
