---
id: ser.JonGuiDataScene
proto: jon_shared_data_scene.proto
package: ser
type: message
---

# JonGuiDataScene

**Source:** `jon_shared_data_scene.proto`

## Description

What the FULL-AUTO scene classifier thinks the world is, per channel. Published every state tick by eutropia's `Isp3aHost` from the `scene_day` guest (`mods/isp3a/scene/`, the third sandboxed 3A guest beside `ae_day` and `awb_day`). It is a REPORT, never a control surface: the operator's latch is `cmd.DayCamera.SceneAuto` / `cmd.HeatCamera.SceneAuto`, and the mode the pipeline actually runs stays `JonGuiDataCameraDay.fx_mode` / `JonGuiDataCameraHeat.fx_mode`.

Read `shadow` first: it says whether ANYTHING is acting on the picture. While it is true, `day_mode` is the mode auto WOULD select and nothing is driving it, so a `day_class` disagreeing with the running `fx_mode` is the system working as designed. While it is false the host is commanding that mode through `cmd.{Day,Heat}Camera.SetFxMode`. Either way the RUNNING mode stays `camera_{day,heat}.fx_mode` — nothing in this block is a readback. A class scores 0..1 per tick; the incumbent changes only when a challenger beats it by a margin for a dwell, and the guest never evaluates during an AE or AWB transient.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | day_auto | bool | - |
| 2 | day_class | [[proto/ser.JonGuiDataSceneClass]] | defined enum value only |
| 3 | day_challenger | [[proto/ser.JonGuiDataSceneClass]] | defined enum value only |
| 4 | day_hold_s | uint32 | - |
| 5 | day_hold_reason | string | max-len: 15 |
| 6 | day_mode | [[proto/ser.JonGuiDataFxModeDay]] | defined enum value only |
| 7 | day_scores | repeated float | max-items: 5, each float: >= 0, <= 1 |
| 8 | heat_auto | bool | - |
| 9 | heat_class | [[proto/ser.JonGuiDataHeatSceneClass]] | defined enum value only |
| 10 | heat_challenger | [[proto/ser.JonGuiDataHeatSceneClass]] | defined enum value only |
| 11 | heat_hold_s | uint32 | - |
| 12 | heat_hold_reason | string | max-len: 15 |
| 13 | heat_mode | [[proto/ser.JonGuiDataFxModeHeat]] | defined enum value only |
| 14 | heat_scores | repeated float | max-items: 2, each float: >= 0, <= 1 |
| 15 | shadow | bool | - |




## Field Notes


### day_auto (#1)

The operator's day full-auto latch, as last commanded by `cmd.DayCamera.SceneAuto.enable`. While false the classifier still runs and still publishes — it is a scene report, not a mode driver — so the scores below are live either way. What the latch gates is the ACT: with it on, and the guest's report settled, the host emits `cmd.DayCamera.SetFxMode` for `day_mode`. With it off nothing is commanded, and `shadow` says so.


### day_class (#2)

The class the guest currently HOLDS for the day channel (its incumbent), as a `JonGuiDataSceneClass`. `UNSPECIFIED` means it has no usable input (see `day_hold_reason`), not "no scene". The `defined_only` constraint rejects a wire value outside the enum, which would otherwise decode as a class nobody defined.


### day_challenger (#3)

The class currently beating the incumbent, if any — `UNSPECIFIED` when the incumbent is also the best-scoring class. A UI shows this as a PENDING switch; it is not a decision. Constrained to defined enum values for the same reason as `day_class`.


### day_hold_s (#4)

How long the current challenger has held its lead, in whole seconds; 0 when there is no challenger. Reaching the guest's configured dwell is what promotes a challenger to incumbent.


### day_hold_reason (#5)

WHY the incumbent has not changed, as a short stable ASCII token never longer than 15 bytes (the `max_len` bound is the ABI's fixed-width reason field): `ok` (no challenger), `dwell` (leading but not long enough), `margin` (leading by less than the margin), `transient` (an AE or AWB transient — the guest does not evaluate at all), `ratelimit` (a switch happened too recently), `noinput` (no usable statistics), `unconfirmed` (dwell and margin satisfied but the class is still refused), `init` (nothing decided yet). The vocabulary is the guest's own (`mods/isp3a/scene/scene_classes.h`), written straight into the ABI rather than mapped by the host, so there is no second copy to drift.


### day_mode (#6)

The day FX mode the held class maps to, as a `JonGuiDataFxModeDay`. Read it with `shadow`: true means this is what auto WOULD select and nothing is driving it; false means it is what auto is driving the camera toward. Never a readback — the running mode is `camera_day.fx_mode`. `DEFAULT` (0) is legal here, unlike in `cmd.DayCamera.SetFxMode`: a publish before the first decision legitimately reports the default. Constrained to defined enum values.


### day_scores (#7)

One score in 0..1 per day class in `JonGuiDataSceneClass` order starting at DAY — index 0 = DAY, 1 = DUSK, 2 = NIGHT, 3 = FOG, 4 = OVERCAST; `UNSPECIFIED` is never scored, so the list is one shorter than the enum and `max_items` is 5. EMPTY (not zeroed) when the guest scored nothing this tick: a vector of zeros is a measurement and absence is not, and the two must not print the same; `day_hold_reason` then says why. The per-item 0..1 bound is the score's own range.


### heat_auto (#8)

The operator's heat full-auto latch, as last commanded by `cmd.HeatCamera.SceneAuto.enable`. Same semantics as `day_auto`.


### heat_class (#9)

The class the guest currently holds for the thermal channel, as a `JonGuiDataHeatSceneClass`. `UNSPECIFIED` with `heat_hold_reason` = `noinput` is the EXPECTED state on every box today: the pre-AGC signal the heat classifier needs is not published by anything (see the enum page). Do not read it as a fault, and do not fill it from post-AGC contrast. Constrained to defined enum values.


### heat_challenger (#10)

The thermal class currently beating the incumbent, if any — `UNSPECIFIED` when there is none. A pending switch, not a decision. Constrained to defined enum values.


### heat_hold_s (#11)

How long the thermal challenger has held its lead, in whole seconds; 0 when there is no challenger.


### heat_hold_reason (#12)

Why the thermal incumbent has not changed — the same token vocabulary and the same 15-byte bound as `day_hold_reason`.


### heat_mode (#13)

The heat FX mode the held thermal class maps to — what the guest WOULD select, as a `JonGuiDataFxModeHeat`. Not a command; `DEFAULT` (0) is legal as a report. Constrained to defined enum values.


### heat_scores (#14)

One score in 0..1 per thermal class in `JonGuiDataHeatSceneClass` order starting at HIGH_CONTRAST — index 0 = HIGH_CONTRAST, 1 = LOW_CONTRAST, so `max_items` is 2. EMPTY when nothing was scored, for the same reason as `day_scores`.


### shadow (#15)

NOTHING IS ACTING ON THE PICTURE. True while neither channel is armed, false as soon as one is — where a channel is armed when its `*_auto` latch is on AND the guest is not reporting `noinput` for it.

So `shadow` false means some mode on this device is being chosen by the classifier rather than by the operator, and a UI must render the modes as automatic. `shadow` true means every mode is whatever was last set by hand, whichever way the latches read: the thermal half reports `noinput` on every box today (nothing publishes the pre-AGC drive it needs), so turning the heat latch on by itself does NOT clear this bit.

⚠ It is NOT the guest's own shadow bit. The WASM classifier has no act path at all — it emits no command record and no `reload_params`, and asserts `ISP3A_OF_SCENE_SHADOW` about itself unconditionally on every build there is. Publishing that bit here would read as "nothing is acting" on a device whose mode the host is driving. The guest's own bit is not lost: it rides eutropia's `3A scene` log line as `guest_shadow`, beside the host's `day_armed` / `heat_armed`.



