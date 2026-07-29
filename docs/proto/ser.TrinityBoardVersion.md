---
id: ser.TrinityBoardVersion
proto: opaque/trinity_tracking.proto
package: ser
type: message
---

# TrinityBoardVersion

**Source:** `opaque/trinity_tracking.proto`

## Description

Identifies **which physical board** a pose refers to.

This is data, not a schema version. `JonOpaquePayload.version` already carries the payload's
wire-format version, and the two move independently: a schema change does not reprint the board,
and a new board revision does not change this message's shape. A single conflated "version" would
make a reprint indistinguishable from a wire bump.

`geometry_sha256` hashes the board's geometry manifest, which is the one home for every board
dimension. Pinning that hash pins the geometry a pose was computed against exactly, so a reprint
from an edited manifest is visibly a different board rather than a silent scale error.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | family | string | min-len: 1 |
| 2 | major | uint32 | - |
| 3 | minor | uint32 | - |
| 4 | geometry_sha256 | string | pattern: ^[0-9a-f]{64}$ |




## Field Notes


### family (#1)

Names which **kind** of board this is — for example `ring-trinity` — with `major` and `minor` giving the revision within that family.

`min-len: 1` rejects the empty string, so a board identity always names its family. Note that `major` and `minor` carry no constraint at all, which makes `0.0` a legal revision: `family` is the only part of the human-readable tuple the schema insists be populated, and `geometry_sha256` is what actually pins the dimensions.


### geometry_sha256 (#4)

sha256 of the board's geometry manifest (`boards/<board>.json`). That manifest is the one home for every board dimension, so hashing it pins the exact geometry a pose was computed against. A reprint from an edited manifest is a different board and this field says so, where the `family` / `major` / `minor` tuple would not — an edit that does not bump the revision is invisible to the tuple while necessarily changing the digest.

`^[0-9a-f]{64}$` is the canonical sha256 digest form: exactly 64 hex characters, lowercase only. Uppercase is rejected, so two digests can be compared as plain strings with no case folding and no length check first.

The pattern constrains the **shape** of the identifier and nothing more. Nothing in the schema resolves the manifest or verifies the digest against its contents, so a well-formed hash naming a manifest no consumer holds still validates — this is an identity, not a lookup.



