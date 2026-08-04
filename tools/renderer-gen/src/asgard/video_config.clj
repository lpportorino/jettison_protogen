(ns asgard.video-config
  "The one home for the eight native stream names. The telemetry
   boundary derives its closed stream enum from these keys; the video transport
   and demux layers carry their transport/channel contract independently in the
   shared Rust wire crate.")

(set! *warn-on-reflection* true)

(def streams
  "Video stream configs: codec, source identity, and OSD variant.

   NO PORT WIRING LIVES HERE, AND THAT IS A PROPERTY OF THIS REPOSITORY RATHER
   THAN AN OMISSION. This tree is public; which UDP port carries which stream is
   deployment topology, and `docs/INTERFACE-CONTRACTS.md`'s own guardrail says in
   those words that it lives in the consuming repos and not here. A `:wt-port`
   key did sit in this map, described as producer metadata that no consumer
   reads — and in THIS checkout that was measurably true: nothing outside
   `asgard.schema`'s `(keys streams)` reads this map at all, so the port values
   documented a producer to a tree with no producer in it. A copy of this
   namespace living beside its actual producer is a different question and this
   deletion is not a claim about it; the values were not wrong, they were in the
   wrong repository. DO NOT REINTRODUCE THE KEY ON A RE-SYNC — see
   `.claude/rules/renderer-gen.md`, which records this alongside the prose
   genericization for exactly that reason.

   H.264 streams use avc1 with profile/level matching actual SPS. The level is
   `level_idc` in the codec string's last byte, and `level_idc` is the level
   times ten — so 0x29 is 41 is Level 4.1. This row read 4.0 for most of its
   life, which the other three refute between them: 0x1f is 31 is 3.1, and 0x1e
   is 30 is 3.0, so the table established the rule its own first row broke.
   - day_video:    Main Profile (0x4d), constraints 0x0c, Level 4.1 (0x29)
   - heat_video:   Main Profile (0x4d), constraints 0x0c, Level 3.1 (0x1f)
   - preview_day:  Main Profile (0x4d), constraints 0x0c, Level 3.0 (0x1e)
   - preview_heat: Main Profile (0x4d), constraints 0x0c, Level 3.0 (0x1e)
   AV1 streams use av01 Main Profile, 8-bit:
   - day_av1:          av01.0.19M.08 — 1920x1080 live day
   - heat_av1:         av01.0.19M.08 — 960x720 live thermal (see the note below)
   - preview_av1_day:  av01.0.04M.08 — 480x270 AV1 preview day
   - preview_av1_heat: av01.0.04M.08 — 450x360 AV1 preview thermal
   All H.264 streams use Main Profile — NVENC on Orin produces Main for both
   full-res recording and preview paths.
   H.264 streams use avc1 (not avc3) codec strings; GStreamer h264parse handles
   the Annex B → AVC conversion when no description is provided.
   All streams get a native WASM OSD overlay (live_day or live_thermal package).
   OSD dims are the native WASM render size (day=1920x1080, thermal=900x720).
   GPU bilinear filtering downscales automatically for smaller viewports.

   :width/:height and :original-width/:original-height ARE TWO DIFFERENT FACTS,
   not two spellings of one. :width/:height is what the DECODER emits;
   :original-width/:original-height is the full-res source the stream derives
   from, used for the GPU canvas backing store so a low-res preview is not a
   tiny render target. They therefore coincide on a full-res stream and diverge
   on a preview, where the preview carries its full-res counterpart's dims — and
   :osd-width/:osd-height equals :original-* on every row.

   ONE ROW BREAKS THAT AND IT IS UNRESOLVED HERE. `heat_av1` is a LIVE thermal
   stream, so by the rule above its :width should equal its :original-width, and
   it does not: 960 against 900. Everything else in reach says the thermal
   source is 900x720 — `heat_video`, which is the same source in H.264; the
   :original-* and :osd-* of all four thermal rows; and the CV pipeline's own
   timing prose in the generated proto docs, which sizes the heat channel at
   900x720. Against that, one number.

   DO NOT 'CORRECT' IT FROM THIS FILE, and do not explain it away either. The
   tempting explanation is encoder padding, and the arithmetic refutes it: 960
   is the 64-alignment of 900, but 720 is not 64-aligned and would have become
   768, while 16-alignment would have given 912x720. An alignment mechanism
   applies to both axes, so no standard block size produces 960x720 from
   900x720. What that leaves is a genuine question this tree cannot answer,
   because nothing here reads the value and no producer is present: is the AV1
   thermal encoder configured to emit 960 wide, or is 960 a stale number? The
   measurement that settles it is the sequence header of a real `heat_av1`
   stream — its frame width, and its render width if they differ. Until someone
   runs that, changing either number would be inventing a resolution."
  {"day_video" {:codec "avc1.4d0c29"
                :width 1920
                :height 1080
                :original-width 1920
                :original-height 1080
                :osd-variant "live_day"
                :osd-width 1920
                :osd-height 1080}
   "heat_video" {:codec "avc1.4d0c1f"
                 :width 900
                 :height 720
                 :original-width 900
                 :original-height 720
                 :osd-variant "live_thermal"
                 :osd-width 900
                 :osd-height 720}
   "preview_day" {:codec "avc1.4d0c1e"
                  :width 480
                  :height 270
                  :original-width 1920
                  :original-height 1080
                  :osd-variant "live_day"
                  :osd-width 1920
                  :osd-height 1080}
   "preview_heat" {:codec "avc1.4d0c1e"
                   :width 450
                   :height 360
                   :original-width 900
                   :original-height 720
                   :osd-variant "live_thermal"
                   :osd-width 900
                   :osd-height 720}
   "day_av1" {:codec "av01.0.19M.08"
              :width 1920
              :height 1080
              :original-width 1920
              :original-height 1080
              :osd-variant "live_day"
              :osd-width 1920
              :osd-height 1080}
   "heat_av1" {:codec "av01.0.19M.08"
               :width 960
               :height 720
               :original-width 900
               :original-height 720
               :osd-variant "live_thermal"
               :osd-width 900
               :osd-height 720}
   "preview_av1_day" {:codec "av01.0.04M.08"
                      :width 480
                      :height 270
                      :original-width 1920
                      :original-height 1080
                      :osd-variant "live_day"
                      :osd-width 1920
                      :osd-height 1080}
   "preview_av1_heat" {:codec "av01.0.04M.08"
                       :width 450
                       :height 360
                       :original-width 900
                       :original-height 720
                       :osd-variant "live_thermal"
                       :osd-width 900
                       :osd-height 720}})