(ns asgard.video-config
  "The one Asgard-side home for the eight native stream names. The telemetry
   boundary derives its closed stream enum from these keys; Bifrost and Midgard
   carry their transport/channel contract independently in the shared Rust
   wire crate.")

(set! *warn-on-reflection* true)

(def streams
  "Video stream configs: codec, source identity, legacy producer port, and OSD variant.
   The `:wt-port` key is retained as producer metadata only; Midgard receives
   every channel over the single native Bifrost connection and never dials it.
   H.264 streams use avc1 with profile/level matching actual SPS:
   - day_video:    Main Profile (0x4d), constraints 0x0c, Level 4.0 (0x29)
   - heat_video:   Main Profile (0x4d), constraints 0x0c, Level 3.1 (0x1f)
   - preview_day:  Main Profile (0x4d), constraints 0x0c, Level 3.0 (0x1e)
   - preview_heat: Main Profile (0x4d), constraints 0x0c, Level 3.0 (0x1e)
   AV1 streams use av01 Main Profile, 8-bit:
   - day_av1:          av01.0.19M.08 — 1920x1080 live day
   - heat_av1:         av01.0.19M.08 — 960x720 live thermal
   - preview_av1_day:  av01.0.04M.08 — 480x270 AV1 preview day
   - preview_av1_heat: av01.0.04M.08 — 450x360 AV1 preview thermal
   All H.264 streams use Main Profile — NVENC on Orin produces Main for both
   full-res recording and preview paths.
   H.264 streams use avc1 (not avc3) codec strings; GStreamer h264parse handles
   the Annex B → AVC conversion when no description is provided.
   All streams get a native WASM OSD overlay (live_day or live_thermal package).
   OSD dims are the native WASM render size (day=1920x1080, thermal=900x720).
   GPU bilinear filtering downscales automatically for smaller viewports.
   :original-width/:original-height = the full-res the stream derives from,
   used for the GPU canvas backing store so low-res previews aren't a tiny
   render target (the decoder still runs at :width/:height). Full-res streams
   use their own dims; previews use their full-res counterpart."
  {"day_video" {:codec "avc1.4d0c29"
                :width 1920
                :height 1080
                :wt-port 4088
                :original-width 1920
                :original-height 1080
                :osd-variant "live_day"
                :osd-width 1920
                :osd-height 1080}
   "heat_video" {:codec "avc1.4d0c1f"
                 :width 900
                 :height 720
                 :wt-port 4087
                 :original-width 900
                 :original-height 720
                 :osd-variant "live_thermal"
                 :osd-width 900
                 :osd-height 720}
   "preview_day" {:codec "avc1.4d0c1e"
                  :width 480
                  :height 270
                  :wt-port 4089
                  :original-width 1920
                  :original-height 1080
                  :osd-variant "live_day"
                  :osd-width 1920
                  :osd-height 1080}
   "preview_heat" {:codec "avc1.4d0c1e"
                   :width 450
                   :height 360
                   :wt-port 4100
                   :original-width 900
                   :original-height 720
                   :osd-variant "live_thermal"
                   :osd-width 900
                   :osd-height 720}
   "day_av1" {:codec "av01.0.19M.08"
              :width 1920
              :height 1080
              :wt-port 4082
              :original-width 1920
              :original-height 1080
              :osd-variant "live_day"
              :osd-width 1920
              :osd-height 1080}
   "heat_av1" {:codec "av01.0.19M.08"
               :width 960
               :height 720
               :wt-port 4081
               :original-width 900
               :original-height 720
               :osd-variant "live_thermal"
               :osd-width 900
               :osd-height 720}
   "preview_av1_day" {:codec "av01.0.04M.08"
                      :width 480
                      :height 270
                      :wt-port 4092
                      :original-width 1920
                      :original-height 1080
                      :osd-variant "live_day"
                      :osd-width 1920
                      :osd-height 1080}
   "preview_av1_heat" {:codec "av01.0.04M.08"
                       :width 450
                       :height 360
                       :wt-port 4093
                       :original-width 900
                       :original-height 720
                       :osd-variant "live_thermal"
                       :osd-width 900
                       :osd-height 720}})