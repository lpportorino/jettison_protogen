---
id: ui.SubjectDeclaration
proto: ui/ui_ast.proto
package: ui
type: message
---

# SubjectDeclaration

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | name | string | min-len: 1, max-len: 63 |
| 2 | type | [[proto/ui.SubjectType]] | defined enum value only |
| 3 | int_initial | int32 | - |
| 4 | string_initial | string | max-len: 255 |


## Oneofs


### initial

Fields: #3, #4





## Field Notes


### name (#1)

Unique subject identifier, e.g. `zoom_level`. Non-empty, and bounded at 63 because subject names are 64-buffered everywhere they are stored or referenced.


### type (#2)

The subject's value type. Closed to defined `SubjectType` members — this vocabulary exposes INT and STRING, and the remaining LVGL subject types are renderer-internal.


### string_initial (#4)

Initial value for a STRING subject, applied at load time. The 255-character bound matches `SubjectValue.string_value`, so a value legal to declare is always legal to assign later.



