# Renderer measurement work

## RENDER-CONTRAST-001 — resolved non-text boundary contrast

Status: open instrument work; the associated visual finding is **UNCERTAIN,
DEFERRED TO THE INSTRUMENT**. This is neither a readability pass nor an exemption.

The 2026-10-08 dependency-upgrade review found faint vanilla outlines in
`lv_target_overlay/default/medium/hidden-labels`; the asgard siblings were
visibly clearer. The finding predates the upgrade. All three family images
were opened again before this disposition. The fresh DOM identifies three
`lv_obj` boxes at child paths `[0 0 0]`, `[0 0 1]`, and `[0 0 2]`. It exposes
the ancestor backdrop `#282b30` but not the resolved border color needed to
settle the finding. No numeric contrast ratio was inferred from JPEG pixels.

The required instrument must obtain the actual renderer-resolved border,
opacity, composition and backdrop for the visible boundary, and determine
whether the boundary conveys information or is decorative. Its acceptance
criteria must be appropriate to non-text boundaries and justified under
[the measurement contract](UI-QUALITY-CONTRACTS.md), sections 0 and 6.2;
text/glyph thresholds cannot be borrowed. It must report missing inputs and
uncertain measurements explicitly, carry positive and failing controls, and
recheck this exact card across all three gallery families.

Close this work item only after that instrument settles the finding and any
confirmed defect is fixed and re-rendered. The renderer measurement maintainer
owns this work item; no implementation or completion date is implied. There
is no production exemption entry and no changed theme value associated with
the deferral.
