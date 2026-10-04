# Native LVGL API boundary contracts

Run from the repository root in the official development container:

```sh
python3 renderer/tools/lvgl-api-selftest.py --out /tmp/lvgl-api-baseline
python3 renderer/tools/lvgl-api-selftest.py --mutations --out /tmp/lvgl-api-mutations
```

The output directory must be fresh. Both commands compile and execute release and
development configurations and run twelve rejection canaries, including a real
native assertion fault and an emptied native case. Parser canaries cover absent
and zero-assertion summaries, missing or duplicate cases, inconsistent failure
counts or exits, duplicate summaries, the exact assertion budget, and a foreign
suite's assertion count. The shared classifier requires callers to supply an
explicit positive assertion budget. Native API calls supply 1,165; the separate
global-subject driver supplies 74, or 75 for its real assertion canary, and checks
reciprocal budget rejection against actual passing output. Every ordinary run requires the exact
116-case population and 1,165 assertions, including all public flag mappings, and each native case
must execute assertions. The mutation command repeats both baselines at
the end and records source hashes, compiler identity, exact compile commands,
generated oracles, changed source copies, logs, and attribution in `report.json`.
It never changes production source. Run independently of writers to the renderer
headers, configuration, implementation, or these probe files: input drift fails
the campaign.

The C probe includes the actual `renderer.c` translation unit and uses real
nanopb encoding and decoding. Native section garbage collection discards
unreachable widget and drawing code. LVGL boundary doubles observe calls,
identity, arguments, and allocation ownership; they do not implement rendering.
The native compiler is the owner's `cc`, using GCC's `-std=c2x` spelling of
C23 and the production strict warning floor. The sole native
exception, `-Wno-attributes`, accepts WebAssembly import attributes in the
unchanged host-import declarations. It does not change production build flags.

The flag oracle is generated from LVGL's public `lv_obj.h`: singleton flag bits,
public setters, public aliases, user flag indices, and property declarations.
It never reads the renderer dispatch table. Discovery requires exactly 31 bits,
partitioned into 24 dedicated setters, four user indices, and three compatibility
properties. Each bit is independently set and cleared, with call counts and
unrelated state preservation checked. Mixed, all, empty, and unknown-bit masks
exercise loop and selection boundaries. Each compatibility property has both
rejection and successful controls, including the persistent load-error latch.

Subject cases verify deletion and clearing of every occupied registry slot,
idempotent empty reset, reset of diagnostic counters, reader registration, and
real protobuf INT/STRING declaration decoding. Allocation failure must latch the
load error without advancing the registry or calling a value setter. String
buffer ownership, default and explicit initial values, and subject type are
checked directly.

Comparison cases call the actual binding helper for EQ and NE. They check
allocation failure, cleanup descriptor failure before subscription, observer
registration failure with exact descriptor rollback, copied observer fields,
and invoke the real deletion callback to verify ownership release. The cleanup
descriptor regression was observed failing before the cleanup-first fix.
The renderer translation unit's allocator alone is intercepted; nanopb
and libc are unchanged. Bounded typed storage makes duplicate frees observable
assertion failures rather than crashes. Comparison value semantics are covered
by the separate real-WASM tests.

Color cases exercise EQ, NE, GT, GTE, LT and LTE through the actual color binding
and observer. Each operator covers allocation, cleanup and observer failures,
plus successful registration, its immediate value, values on both sides of the
reference (INT32_MIN/MAX), repeated polarity changes, text-color channel and
selector identity, removal of the exact local text override, and deletion.
Registration reserves cleanup before subscribing; observer failure removes only
the returned descriptor, frees the owned data once and latches the load error.
An unrelated event descriptor stays registered. The maintained cases were
observed red against the unfixed renderer with all legacy controls passing;
the independent resource-failure probe separately reported six failing color
assertions before the repair.

The allocator double uses a union of both real binding payload types, verifies
the requested size, tracks live allocation ownership and counts invalid/double
frees. Observer and event doubles reject callbacks registered without an owner.
Wrong callback identities are observed rather than executed with incompatible
payloads, preserving assertion failures instead of infrastructure crashes. The
neighbor-descriptor fault proves preservation of unrelated cleanup is non-vacuous.
This remains a native boundary contract; rendering/theme behavior belongs to the
real-WASM battery.

Configuration assertions require XRGB8888 as the LVGL default, the internal
ThorVG implementation, and object properties under both headers. The real display
sets its framebuffer format separately; this probe does not replace framebuffer
or theme parity tests. The global subjects in `main.c` and their entry-point
lifecycle are outside this translation unit.

Every eligible mutation must compile, exit with the assertion-failure status,
report a positive executed assertion count, name its intended failed case, and
retain a named passing control. Exact anchors and changed-file hashes establish
that the fault landed. A survivor, wrong failure, compilation error, crash,
timeout, or source drift fails the campaign. Separate deliberate compilation,
abort, and timeout canaries prove those infrastructure outcomes are not counted
as mutation kills. The process supervisor starts a fresh process group and kills
remaining descendants on every exit. Two real fork canaries prove cleanup both
when a child holds standard output open past the deadline and when its output
is closed and the parent exits successfully. Timeout logs retain child output.
Read the individual logs as well as the aggregate result.
