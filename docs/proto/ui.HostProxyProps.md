---
id: ui.HostProxyProps
proto: ui/ui_ast.proto
package: ui
type: message
---

# HostProxyProps

**Source:** `ui/ui_ast.proto`

## Description

*No description yet.*

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | proxy_id | string | min-len: 1, max-len: 63 |
| 2 | mode | [[proto/ui.ProxyMode]] | defined enum value only |
| 3 | min_w | int32 | - |
| 4 | min_h | int32 | - |
| 5 | max_w | int32 | - |
| 6 | max_h | int32 | - |
| 7 | handle_size | uint32 | - |
| 8 | z | int32 | - |




## Field Notes


### proxy_id (#1)

Stable host-side join key for the proxied element. Non-empty, and bounded at 63; it survives tree rebuilds, which is what lets the host keep compositing the same element across a patch.


### mode (#2)

Initial proxy mode. When a `mode` binding is present the subject is the source of truth and this value is ignored after attach.



