---
id: cmd.System.SetLocalization
proto: jon_shared_cmd_system.proto
package: cmd.System
type: message
---

# SetLocalization

**Source:** `jon_shared_cmd_system.proto`

## Description

Sets the system interface language/locale to one of the supported localizations: English (EN), Ukrainian (UA), Arabic (AR), or Czech (CS). This allows the UI to display text and localized content in the user's preferred language.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | loc | [[proto/ser.JonGuiDataSystemLocalizations]] | defined enum value only, not in: 0 |



## Interaction

- **Category:** :settings
- **UI Pattern:** :enum-picker
- **Feedback:** :fire-and-forget


### Purpose

Sets the system language/localization preference


### Related State

- [[proto/proto/proto/proto/proto/proto/ser.JonGuiDataSystem]]




### Implementation Notes

Changes UI language and regional formatting



## Field Notes


### loc (#1)


#### Metadata

- **Semantic Type:** :enum-label
- **Display Format:** `Language: {value}`



