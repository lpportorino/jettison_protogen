---
id: ui.SubjectValue
proto: ui/ui_ast.proto
package: ui
type: message
---

# SubjectValue

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | name | string | min-len: 1, max-len: 63 |
| 2 | int_value | int32 | - |
| 3 | string_value | string | max-len: 255 |


## Oneofs


### value

Fields: #2, #3





## Field Notes


### name (#1)

The subject being updated, naming a `SubjectDeclaration.name`. Non-empty and bounded at 63 on the same rule as the declaration.


### string_value (#3)

The string arm of the value oneof, bounded at 255 characters — the same bound a STRING subject's initial value carries.



