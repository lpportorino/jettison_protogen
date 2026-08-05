# LVGL Controls WASM Module — wasi-sdk Build
#
# Produces: output/controls.wasm (generic LVGL renderer with nanopb protobuf)
# UI definitions are pushed as protobuf AST at runtime.
WASI_SDK ?= /opt/wasi-sdk
CC := $(WASI_SDK)/bin/clang
CXX := $(WASI_SDK)/bin/clang++
SYSROOT := $(WASI_SDK)/share/wasi-sysroot
TARGET := wasm32-wasip1
# Depfiles are included before the `all` rule below. Once they exist, make
# would otherwise adopt the first included object as its implicit default and
# a plain `make -f wasm.mk BUILD=...` could stop without checking the module.
.DEFAULT_GOAL := all

# Build mode toggle — RELEASE is the default and the ONLY shipped artifact.
#   BUILD=release (default): -O2 -flto -> output/controls.wasm. The four
#     tolerance-0 gates (harness/morph-parity/matrix/demo-parity — renderer.mk
#     records the source repo's differing names) validate THIS exact artifact;
#     its flags must stay byte-identical, so the release branch is unchanged.
#     They are NOT gate-cached — renderer.mk's header records the cache
#     wrapping as deliberately stripped here — so every run re-judges the
#     artifact rather than replaying a cached verdict. That is a separate
#     question from whether the artifact is REBUILT: `wasm` shells this file
#     and plain make freshness decides that, so an up-to-date artifact is
#     re-judged without being relinked.
#   BUILD=dev: -O0 -g -gdwarf-5, NO -flto -> output/controls.dev.wasm. With LTO
#     off every build/*.o is real native wasm (not LLVM IR), so -O2 backend
#     codegen does NOT re-run at link — the cached .o's relink in seconds. DWARF
#     makes it debuggable. NEVER shipped: the release gates always rebuild the
#     -O2 -flto output/controls.wasm regardless of this toggle.
# Additive by construction: dev uses a SEPARATE OUT name, active only under
# BUILD=dev, so it cannot perturb the release artifact.
BUILD ?= release

ifeq ($(BUILD),dev)
OPT := -O0 -g -gdwarf-5
OUT := output/controls.dev.wasm
# Keep the LVGL feature configuration mode-keyed just like the object tree.
# The dev header is deliberately seeded byte-for-byte from the release header;
# later instrumentation work can change it without perturbing release. A
# per-BUILD include directory was chosen over -DLV_CONF_PATH because release
# keeps its historical compile command exactly, while the alternative requires
# a quote-escaped macro to be kept in sync across the C and C++ flag sets.
LV_CONF := config/dev/lv_conf.h
LV_CONF_INCLUDE := -Iconfig/dev
# Both modes keep the SjLj transform (ThorVG's tvgSwRle.cpp #errors without
# `-mllvm -wasm-enable-sjlj`). But with -flto OFF the dev link has no whole-program
# codegen to lower the __wasm_setjmp family inline, so dev links wasi-sdk's
# libsetjmp.a (-lsetjmp) to supply it. Release needs no -lsetjmp — the -flto link
# lowers SjLj inline.
SJLJ_LIB := -lsetjmp
else
OPT := -O2 -flto
OUT := output/controls.wasm
LV_CONF := lv_conf.h
LV_CONF_INCLUDE :=
SJLJ_LIB :=
endif

# Strict warning set — applied to APPLICATION sources only (src/*.c), never to
# vendored LVGL/nanopb. -Wconversion/-Wsign-conversion are deliberately NOT here:
# the renderer is built on intentional proto-int -> LVGL-enum direct casts, so
# they would be pure noise; value-correctness is guarded by the parity gate + the
# planned tolerance-0 visual differential instead.
# The application C standard, in ONE place. It is NOT part of $(CFLAGS)
# because vendored LVGL/nanopb are not compiled under it — but anything that
# reconstructs the app compile line (the compile-db target, hence clang-tidy)
# MUST include it, or `static_assert` and friends fail to parse in a tree the
# compiler accepts. Measured: omitting it produced three phantom parse errors
# on a `static_assert` that the real build compiles cleanly.
APP_STD := -std=c23

WARN_FLAGS := -Wall -Wextra -Werror \
              -Wshadow \
              -Wunused-function -Wunused-variable -Wunused-but-set-variable \
              -Wformat=2 -Wformat-security

# -MMD -MP: emit a per-object .d depfile listing every header the TU opened
# (transitive), plus phony targets so a deleted header does not break the build.
# The `-include` of these depfiles (below) is what makes a HEADER edit rebuild
# its dependents — the general form of the lv_conf.h prereq below (which only
# guarded that ONE header; a gesture.h/cmd_patch.h/renderer.h edit silently
# linked stale objects, and an inter-TU struct-size change is an ABI mismatch).
DEPFLAGS := -MMD -MP
CFLAGS := --target=$(TARGET) --sysroot=$(SYSROOT) \
          $(OPT) \
          $(DEPFLAGS) \
          -DLV_CONF_INCLUDE_SIMPLE \
          -DPB_FIELD_32BIT \
          -DPB_ENABLE_MALLOC \
          -DHAS_NANOPB \
          $(LV_CONF_INCLUDE) -I. -Ilvgl -Isrc -Igenerated
CXXFLAGS_COMPILE := --target=$(TARGET) --sysroot=$(SYSROOT) \
            $(OPT) \
            $(DEPFLAGS) \
            -DLV_CONF_INCLUDE_SIMPLE \
            -fno-exceptions -fno-rtti \
            -mllvm -wasm-enable-sjlj \
            $(LV_CONF_INCLUDE) -I. -Ilvgl -Isrc -Igenerated \
            -std=c++17
CXXFLAGS_LINK := --target=$(TARGET) --sysroot=$(SYSROOT) \
            $(OPT) \
            -DLV_CONF_INCLUDE_SIMPLE \
            -fno-exceptions -fno-rtti \
            $(LV_CONF_INCLUDE) -I. -Ilvgl -Isrc -Igenerated \
            -std=c++17
# The memory triple at the end of this list is a CONTRACT, not three tuning
# knobs. `stack-size` in particular is load-bearing and must not be dropped:
#
#   children_decode_cb recurses once per widget-tree level, and each level costs
#   a widget_ctx_t + a ui_WidgetNode + the nanopb frames around them — 4816 B,
#   from clang -fstack-usage over the recursion cycle (children_decode_cb 4528
#   + pb_decode 64 + pb_decode_inner 192 + decode_callback_field 32). The
#   WASI-SDK default stack is 64 KiB, which carries the decoder about 13 levels
#   before it walks off the stack into a trap. That is FAR below renderer.c's
#   own MAX_DECODE_DEPTH, so without this flag the depth guard is unreachable
#   dead code and an over-deep tree crashes the guest instead of being refused
#   with a diagnostic.
#
# 256 KiB carries the decoder well past MAX_DECODE_DEPTH with room to spare, so
# the declared cap becomes the limit that actually fires. It costs 192 KiB of
# the 8 MiB initial memory. A chain AT the cap peaks at <=165888 B — 63% of the
# reservation — measured by bisecting this number until decode_limits'
# nesting_at_the_declared_cap_loads_clean stops passing (it passes at 165888 and
# fails at 164864, so the figure is exact to the 1 KiB step of that ladder).
#
# THE TRIGGER LIST BELOW USED TO NAME ONLY PER-LEVEL COSTS, AND THAT IS WHY THE
# PREVIOUS FIGURE WENT STALE. It said to re-bisect when raising MAX_DECODE_DEPTH
# or when widening something that lands in the RECURSION FRAME — but the peak
# also contains ONE non-recursive frame, finalize_widget's, which
# children_decode_cb calls after pb_decode returns and which carries the whole
# gesture staging array (CMD_PATCH_MAX_GESTURES x sizeof(cmd_gesture_spec_t),
# ~820 B per entry). Growing that bound costs the peak once rather than per
# level, so it moved the peak while matching no trigger anyone was watching for.
# Re-run the bisection when raising MAX_DECODE_DEPTH; when widening any string
# bound that lands in the per-level frame — whether INLINE in ui_WidgetNode or
# mirrored into widget_ctx_t (binding_entry_t / bind_format_entry_t), which
# costs the same per level; AND when growing a bound that sizes a local in
# finalize_widget. proto/ui/ui_ast.options carries the worked example of a
# proposed string widen that overflowed this reservation.
#
# WHETHER THE OLD FIGURE WAS ALREADY STALE BEFORE THAT BOUND MOVED IS NOT
# MEASURED HERE, and is left open rather than guessed: the re-measured peak is
# further above the old one than this tree's own registry growth accounts for,
# which is a reason to distrust the previous number and not evidence about when
# it stopped being true. Bisecting requires
# forcing a rebuild (touch a source) — this file is not a prerequisite of the
# link rule, so editing the number alone leaves make with nothing to do and the
# PREVIOUS artifact under test. Once the artifact really is rebuilt, the
# decode_limits harness test asserts a chain AT the cap still loads, so an
# under-sized stack fails loudly; without the forced rebuild it asserts nothing
# about the number you just edited.
LDFLAGS := -Wl,--export=malloc -Wl,--export=free \
           -Wl,--export=controls_init \
           -Wl,--export=controls_load_ui \
           -Wl,--export=controls_destroy \
           -Wl,--export=controls_update_state \
           -Wl,--export=controls_apply_patch \
           -Wl,--export=controls_host_message \
           -Wl,--export=controls_pointer_decisions_count \
           -Wl,--export=controls_pointer_decisions_ptr \
           -Wl,--export=controls_pointer_decisions_clear \
           -Wl,--export=controls_pointer_active_count \
           -Wl,--export=controls_cmd_patch_probe \
           -Wl,--export=controls_cmd_spec_decode_probe \
           -Wl,--export=controls_key_event \
           -Wl,--export=controls_text_input \
           -Wl,--export=controls_get_focused_text \
           -Wl,--export=controls_tick \
           -Wl,--export=controls_get_framebuffer \
           -Wl,--export=controls_abi_version \
           -Wl,--export=controls_fb_format \
           -Wl,--export=controls_fb_width \
           -Wl,--export=controls_fb_height \
           -Wl,--export=controls_fb_bpp \
           -Wl,--export=controls_set_breakpoint \
           -Wl,--export=controls_set_theme_dark \
           -Wl,--export=controls_set_theme_family \
           -Wl,--export=controls_set_dpi \
           -Wl,--export=controls_resize \
           -Wl,--export=controls_get_dirty_rect \
           -Wl,--export=controls_get_dirty_rect_ptr \
           -Wl,--export=controls_dump_tree \
           -Wl,--export=controls_dump_draw_palette \
           -Wl,--export=gesture_test_reset \
           -Wl,--export=gesture_test_feed \
           -Wl,--export=gesture_decisions_ptr \
           -Wl,--initial-memory=8388608 \
           -Wl,--max-memory=268435456 \
           -Wl,-z,stack-size=262144 \
           -mexec-model=reactor
# Sorted: link order IS wasm byte layout. TWO different orderings are in play
# and BOTH must be pinned — `find` emits readdir order, while `$(wildcard)` is
# glob(3), which orders by strcoll and therefore by LC_COLLATE. make's own
# $(sort) is strcmp, so it is the one locale-invariant ordering available; every
# discovered source list below is wrapped in it.
#
# The locale half is the one that bites across machines, because it splits HOST
# from CONTAINER rather than checkout from checkout: this host runs
# LANG=en_US.UTF-8 while both toolchain images carry only C/C.utf8/POSIX.
# Measured — without $(sort), $(wildcard lvgl/demos/widgets/*.c) returns
# lv_demo_widgets_analytics.c BEFORE lv_demo_widgets.c under en_US.UTF-8 and
# after it under C, so two builds from identical sources and a byte-identical
# WASI-SDK link a different byte stream.
#
# Sort each glob SEPARATELY, never over the union: a union sort would put
# widgets/assets/img_*.c ahead of widgets/lv_demo_*.c ('a' < 'l') and reorder
# the link for no reason.
LVGL_SRCS := $(sort $(shell find lvgl/src -name '*.c' 2>/dev/null))
NANOPB_SRCS := generated/pb_common.c generated/pb_decode.c generated/pb_encode.c

# ui_input.pb.c: the host->WASM control channel (ui.HostToWasm) that
# controls_host_message pb_decodes (R4 pointer entry + lifecycle).
GEN_SRCS := generated/ui_ast.pb.c generated/ui_input.pb.c

# The shared scalar/enum nanopb binding — the leaf every other jon_shared_*
# message type builds on. Listed by name: `-Igenerated` holds exactly what
# renderer.mk's `generated-projection` list projects, and nothing there is
# discovered by wildcard.
DATA_TYPES_SRCS := generated/jon_shared_data_types.pb.c
FONT_SRCS := $(sort $(wildcard src/font_*.c))
STUB_SRCS := src/wasm_sjlj_stub.c

# Common application sources, shared by both WASM modules. The renderer differs:
#   controls.wasm  <- src/renderer.c     (decodes the proto UI AST — the deployed path)
#   reference.wasm <- src/reference_ui.c (literal lv_* calls — the diff oracle)
COMMON_APP_SRCS := src/main.c src/theme.c src/palette_observer.c src/gesture.c src/svg_decoder.c src/cmd_patch.c $(FONT_SRCS)

# ThorVG C++ sources (internal LVGL build)
THORVG_SRCS := $(sort $(shell find lvgl/src/libs/thorvg -name '*.cpp' 2>/dev/null))

# Object directory for split compilation — keyed by BUILD mode so the release
# (-O2 -flto, LLVM-IR .o) and dev (-O0, native-wasm .o) object caches NEVER mix.
# Toggling `make wasm` <-> `make wasm-dev` is then sound WITHOUT a clean, and each
# mode stays independently incremental. (`clean` removes the whole build/ tree.)
OBJ_DIR := build/$(BUILD)
LIB_OBJS := $(patsubst %.c,$(OBJ_DIR)/%.o,$(LVGL_SRCS) $(NANOPB_SRCS) $(GEN_SRCS) $(DATA_TYPES_SRCS))
STUB_OBJS := $(patsubst %.c,$(OBJ_DIR)/%.o,$(STUB_SRCS))
COMMON_APP_OBJS := $(patsubst %.c,$(OBJ_DIR)/%.o,$(COMMON_APP_SRCS))
RENDERER_OBJ := $(OBJ_DIR)/src/renderer.o
REFERENCE_OBJ := $(OBJ_DIR)/src/reference_ui.o

# lv_demo_widgets (vendored at lvgl/demos, v9.5.0 tag) — linked into
# reference.wasm ONLY: the demo-parity oracle, never deployed.
DEMO_SRCS := $(sort $(wildcard lvgl/demos/widgets/*.c))                    $(sort $(wildcard lvgl/demos/widgets/assets/*.c)) lvgl/demos/lv_demos.c
DEMO_OBJS := $(patsubst %.c,$(OBJ_DIR)/%.o,$(DEMO_SRCS))
THORVG_OBJS := $(patsubst %.cpp,$(OBJ_DIR)/%.o,$(THORVG_SRCS))

# Pull in the -MMD depfiles for every object this mode could build so a header
# edit rebuilds its dependents. The leading `-` ignores absent .d files (the
# first build of a mode, before any object exists) — deps regenerate as objects
# compile. Scoped to $(OBJ_DIR) so the release/dev caches stay independent.
DEP_OBJS := $(LIB_OBJS) $(STUB_OBJS) $(COMMON_APP_OBJS) $(RENDERER_OBJ) \
            $(REFERENCE_OBJ) $(DEMO_OBJS) $(THORVG_OBJS)

-include $(DEP_OBJS:.o=.d)

# $(OUT) is output/controls.wasm (release, default) or output/controls.dev.wasm
# (BUILD=dev).
#
# ── the release link is the wasm phase's largest serial cost. Read first ────
# It runs TWICE per renderer battery ($(OUT) and reference.wasm), neither half
# parallelises, and together they dominate that phase. (Only the wasm lanes
# were timed; whether the link outweighs `fixtures` or `devcards-test` across
# the whole battery is NOT established here.) This block records what that
# time actually IS, and — more usefully — the fix that looks obvious, was
# tried, and DOES NOT WORK. All of it measured on this toolchain at -j12;
# absolute seconds are box- and load-dependent, so the ratios are what carry.
#
# WHERE THE TIME GOES: not in the linker. Linking the post-LTO native object
# alone (-Wl,--save-temps drops it beside the output as $@.lto.o) completes in
# under 0.1s, against ~18s for the identical link driven from the IR objects —
# more than two orders of magnitude. -Wl,-mllvm,-time-passes attributes ~99% of
# the link's wall time to LLVM passes — as an aggregate; the only per-pass
# figure taken was WebAssembly instruction selection, the largest single one
# at ~24%. Under -flto the "link" is not linking: it is
# the backend codegen the compile step deferred (see `objects` below), and it
# runs in ONE thread.
#
# A PREBUILT ARCHIVE OF THE VENDORED TUs DOES NOT BUY WHAT THE TODO PROMISED.
# This sat here as a TODO; it is retired by measurement, not by opinion.
# Collect the vendored objects (LVGL + ThorVG + nanopb + generated pb) with
# llvm-ar and link the archive lazily — -Wl,--why-extract then shows only
# about a THIRD of the members are extracted at all, yet THE CODEGEN WORK IS
# UNCHANGED, which is where the link's time actually goes. Wall clock is a weak
# witness on a shared box, so the decisive evidence is a count, taken from the
# -Wl,--save-temps intermediates: the module handed to codegen shrinks by
# ~0.3%, and the NUMBER OF FUNCTIONS code-generated is identical. The two
# thirds of vendored TUs the archive drops cost nothing to begin with — dead
# before codegen, the usual mechanism being internalize + GlobalDCE, though
# only the CONSEQUENCE was measured here and not the pass that produced it —
# and the cost is optimising and generating code for the LIVE program, which
# an archive does not touch. (An identical function count rules out a CODEGEN
# saving. It does NOT rule out the cost of READING the 344 dropped modules'
# IR. Four interleaved baseline/archive wall pairs, measured but recorded only
# in this commit's message, ran -0.24 / +0.82 / +0.85 / +2.15 seconds — three
# favouring the archive, one against it. So that residual is bounded at
# roughly a second or two, not at zero. A loaded box cannot resolve a gap that
# size, and one pair going the other way is what a noise-dominated measurement
# looks like, which is why this is stated as a bound and not as a win.) An archive under -flto holds LLVM IR,
# so it MOVES no codegen; it changes the link's input order, and the output
# differs by 34 bytes — a difference that was observed, not localized. Do not
# re-litigate without new data.
#
# WHAT WOULD WORK — and why each is a CONTRACT CHANGE, not a perf tweak. The
# four tolerance-0 gates validate THIS exact artifact, so anything shifting
# its bytes is a deliberate re-mint with the oracles re-run:
#   - Vendored TUs at -O2 WITHOUT -flto (real native wasm objects), -flto kept
#     on src/ only. The link drops ~4.5x (~18s -> ~4s) while the compile side
#     costs about a second more (10.33s -> 11.51s, +11%) at -j12, because the
#     per-TU backend work it takes on is exactly what parallelises; total CPU
#     is roughly conserved
#     and the wasm phase drops ~45% cold. Needs -lsetjmp, as BUILD=dev
#     already does and for the same reason. Output is ~220 KB SMALLER and
#     byte-different: app<->LVGL cross-module inlining is gone.
#   - ThinLTO everywhere (-flto=thin, compile and link). Link ~2.2x faster
#     (~18s -> ~8s), compile ~1s more (+10%), wasm phase ~30% less cold.
#     Output is ~18 KB larger and byte-different.
# -Wl,--lto-partitions=N parallelises codegen but loses twice over: no
# reliable wall win here, and the artifact grows up to ~75% as cross-partition
# optimisation is lost.
#
# INCIDENTAL, and NOT a perf lever: ~47% of the RELEASE $(OUT) is DWARF
# (.debug_*) despite no -g in the release flags (BUILD=dev does pass -g, and
# is not what this paragraph is about). None of it comes from our sources — our
# objects carry no debug sections and a trivial hello-world links to the same
# shape —
# it arrives with wasi-sdk's own sysroot libraries. -Wl,--strip-debug removes
# it for a ~47% smaller artifact and saves NO link time (measured), so it is
# purely an artifact-size question, and it too moves the bytes.
all: $(OUT) $(OUT).build-sha

# Compile every TU, but do NOT link. For consumers that need the COMPILATION —
# the flags, the diagnostics, the compile_commands entries — and never read the
# linked module — a downstream dead-export ratchet is the case: it captures the
# real compile commands from this target, then re-runs clang per TU for an AST
# dump. It never opens output/controls.wasm. Named by what it needs rather than
# by a path, because the consumer lives in another repo.
#
# The saving is large and it is a property of -flto: with LTO on, compiling only
# emits LLVM IR and ALL backend codegen is deferred to the link — confirmed
# directly by the phase split recorded above `all`. A consumer that only needs
# compilation therefore pays a fraction of the full build. The MECHANISM above
# is the claim; the numbers only illustrate it, and they are not stable enough
# to be one. The one internally coherent cold observation at -j12 reads
# objects 10.3s / link 17.7s / `all` 28.0s — a ~2.7x saving for a
# compile-only consumer. An older triple (3.3 / 13.2 / 15.2, ~4.6x) is NOT
# reproduced here because it does not add up: its parts exceed its total by
# 1.3s, so at least one of the three is wrong and there is no way to tell
# which. A number that contradicts itself is the defect this block exists to
# retire, so it is dropped rather than carried forward with a caveat.
#
# The prerequisites are $(OUT)'s own, so the object list has ONE home (cohesion):
# a new source group added to the link rule is picked up here for free. What the
# gate relies on is preserved exactly — -Wall -Wextra -Werror -Wunused-function
# are FRONTEND diagnostics, emitted while compiling, so the "a tree that builds
# has no dead static C" oracle is untouched by skipping the link.
objects: $(LIB_OBJS) $(STUB_OBJS) $(COMMON_APP_OBJS) $(RENDERER_OBJ) $(THORVG_OBJS)

# Verifier-only: the independent reference module (not deployed). Built on demand
# by the visual-differential harness, never part of `controls.tar`.
reference: output/reference.wasm

# Every object depends on this BUILD's real LVGL configuration: a config toggle
# (decoder on/off, a feature flag) changes what the #if guards compile —
# without this dep a conf edit leaves stale objects and the change silently
# never ships.
# Library sources: compile without -Werror (LVGL has format-nonliteral warnings)
$(OBJ_DIR)/%.o: %.c $(LV_CONF)
	@mkdir -p $(dir $@)
	$(CC) $(CFLAGS) -c -o $@ $<

# Application sources: compile as C23 with strict warnings. -std=c23 is applied
# HERE (app rule) only — vendored LVGL/nanopb compile with the generic rule below
# at the toolchain default, so a newer standard never destabilizes upstream code.
$(OBJ_DIR)/src/%.o: src/%.c $(LV_CONF)
	@mkdir -p $(dir $@)
	$(CC) $(CFLAGS) $(APP_STD) $(WARN_FLAGS) -c -o $@ $<

# ThorVG C++ sources
$(OBJ_DIR)/%.o: %.cpp $(LV_CONF)
	@mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS_COMPILE) -c -o $@ $<

# ── Provenance stamp: which protogen commit this wasm was BUILT FROM ─────────
# controls.wasm is not committed, so `git submodule update` no longer delivers
# the artifact recorded at the pin — and nothing downstream could tell a build
# of the pinned source from a stale build left in the worktree. A consumer could
# bump the pin across a renderer change, never rebuild, and watch its ENTIRE
# battery go green while shipping the PREVIOUS interpreter (pinned-renderer
# compares the tar against whatever is on disk; controls-tar-fresh re-stages
# that same stale wasm; the screen corpus renders it).
#
# So the link records its own provenance beside the artifact. Consumers compare
# this against their gitlink; anything else is a stale build.
#
# BARE `git rev-parse`, never `git -C`: inside the toolchain container GIT_DIR
# and GIT_WORK_TREE are exported, and they OVERRIDE -C — a `-C` form silently
# reports the wrong repository's HEAD, which is worse than no stamp because it
# reads as authoritative while being false.
#
# Keyed on $@, not a fixed path: $(OUT) is mode-keyed, so a BUILD=dev link would
# otherwise clobber the release stamp with one describing a module that never
# ships. Covered by renderer/.gitignore's output/*.
#
# UNRESOLVABLE DEGRADES TO A REJECT, never to a pass: with no git available the
# value below is the literal string, which matches no gitlink, so the consumer
# fails. "Cannot answer" must read as NO. Dirtiness is likewise a reject — a
# build carrying uncommitted renderer edits is not a build of the pinned source,
# whatever HEAD says.
PROTOGEN_SHA ?= $(shell git rev-parse HEAD 2>/dev/null || echo UNRESOLVABLE-NO-GIT)$(shell git diff --quiet HEAD -- . 2>/dev/null || echo -dirty)

# ── the CONTENT stamp: WHAT WAS COMPILED, written BY the link ────────────────
# The sha stamp above answers "which commit was HEAD when make last ran". That
# is not the same question as "which sources produced this binary", and the gap
# between them is REACHABLE — measured against this very file, on a warm object
# tree, with no compiler invoked:
#
#   commit an edit to the LINK FLAGS in this file, then `make -f wasm.mk all`.
#   Exit 0, no output, controls.wasm byte-identical and its mtime unmoved — and
#   controls.wasm.build-sha rewritten to the NEW commit. The binary on disk was
#   linked without the flag the stamped tree adds. A consumer comparing the
#   stamp against its gitlink passes, over an artifact that does not correspond
#   to the stamped source.
#
# THAT IS NOT ONE BUG WITH ONE PATCH, IT IS A CLASS, and enumerating it is why
# the answer here is a content claim rather than a new prerequisite. Four paths
# reach it, all measured on the real makefile:
#   1. THIS FILE is a prerequisite of nothing, so every flag it carries — the
#      export list, the memory triple, $(OPT), the warning set — can move
#      without a relink (the stack-size block above already records this from
#      the other direction, as the reason a bisection needs a forced rebuild).
#   2. A source whose CONTENT moved but whose MTIME did not: `cp -p`, `rsync
#      -a`, a tar or artifact extract that restores times. make compares times,
#      so the object stays "current" over changed bytes.
#   3. A DELETED source. The prerequisite list shrinks, nothing left in it is
#      newer than the target, and the removed translation unit stays linked in.
#      Simulated by shrinking LVGL_SRCS by 63 files: make ran the stamp recipes
#      and nothing else.
#   4. Any future path that skips or short-circuits the link.
# Adding prerequisites closes the ones somebody thought of. A digest of the
# inputs closes the class, because it compares CONTENT and never asks how the
# link came to be skipped.
#
# "JUST MAKE THIS FILE A PREREQUISITE OF THE LINK" IS THE OBVIOUS FIX FOR (1),
# AND AS A ONE-LINE CHANGE IT IS WORSE THAN NOTHING. Adding `wasm.mk` to $(OUT)
# alone relinks without RECOMPILING, so a $(CFLAGS) or $(OPT) edit would produce
# a binary whose objects still carry the OLD compile flags — and the fresh link
# would then write a fresh, GREEN content stamp over it. To be sound the file
# has to be a prerequisite of every OBJECT as well, exactly as $(LV_CONF) is,
# which means every edit to it — including a comment — rebuilds all of them.
# That is a deliberate cost decision about incremental-build behaviour (the perf
# block above `all` is the ledger it belongs in), not a provenance fix, so it is
# left to be taken on its own terms.
#
# THE CONSEQUENCE, STATED SO IT IS NOT MET AS A SURPRISE: because this file is
# hashed and is a prerequisite of nothing, editing it — a flag OR a comment —
# leaves the artifact untouched and makes the verifier ask for a relink. That is
# the over-hash direction working as designed; the flag and the comment are
# indistinguishable without parsing make semantics, and a stamp that guessed
# would be guessing about the one thing it exists to assert.
#
# THE ONE PROPERTY THAT MAKES IT WORK IS WHO WRITES IT. This stamp is emitted
# by the LINK RECIPE and by nothing else — it is not a target, deliberately, so
# no rule can regenerate it from the current tree while an older binary sits
# beside it. That would be the same defect in a newer coat: a fresh claim over
# stale bytes. A no-op rebuild therefore leaves it exactly as it was, which is
# the correct outcome — it stays TRUE rather than becoming a fresh lie, and a
# tree that has moved since the link now DISAGREES with a recomputation, which
# is what the verifier reports.
#
# The cost of that choice, stated rather than discovered: a warm tree built
# before this stamp existed has no sidecar, and no `make` invocation will mint
# one without relinking. That is not a gap to paper over — a truthful sidecar
# cannot be produced without producing the binary it describes. renderer.mk's
# verifier refuses (never passes) and prints the relink.
#
# ADDITIVE BY CONSTRUCTION: .build-sha is untouched, still written by the same
# rule, still the same single-line shape its readers parse. This is a SECOND
# sidecar beside it, so a consumer gating on the sha keeps passing and adopts
# the stronger claim when it chooses to.
#
# SCOPE, so no reader over-reads the digest: it covers the files this link
# compiles plus the description that compiles them. It does NOT cover the
# TOOLCHAIN — identical inputs under a different WASI-SDK give a different
# binary and the same digest. That axis belongs to the provisioning-parity pair
# in renderer.mk (`wasm-sha-record` / `wasm-sha-verify`), which compares the
# ARTIFACT's own sha256 across the two build paths, and to Dockerfile.base.
#
# The header set is derived from the compile's OWN -I roots rather than typed
# beside them, so a header this build can reach is a header this digest covers.
# All four are present: `-Ilvgl -Isrc -Igenerated` are walked, and `-I.` is the
# renderer root, whose only header today is $(LV_CONF) but which is swept by
# wildcard anyway so a second one cannot arrive uncovered. BUILD=dev's
# `-Iconfig/dev` needs no sweep — $(LV_CONF) IS that directory's only file, and
# it is named below.
# It overhashes slightly — a header no translation unit includes still moves the
# value — and that is the safe direction: the cost is a rebuild that was not
# strictly needed, against a stale binary that reads as fresh.
BUILD_INPUT_HEADERS := $(sort $(wildcard *.h) \
                         $(shell find lvgl src generated \
                           \( -name '*.h' -o -name '*.hpp' \) 2>/dev/null))
BUILD_INPUT_FILES := $(sort $(LVGL_SRCS) $(THORVG_SRCS) $(NANOPB_SRCS) $(GEN_SRCS) \
                       $(DATA_TYPES_SRCS) $(COMMON_APP_SRCS) $(STUB_SRCS) src/renderer.c \
                       $(BUILD_INPUT_HEADERS) $(LV_CONF) wasm.mk)

# NON-VACUITY, and it is load-bearing rather than decorative. If discovery
# collapses — a caller in the wrong directory, a vendored tree absent — the list
# falls back to the paths named literally above, and the writer and the verifier
# then compute the SAME short digest and AGREE. Green over almost nothing, which
# is the defect this stamp exists to catch, reintroduced inside it. The floors
# are per-prefix because any one populated root satisfies a union floor, so a
# root going dark would be invisible under one.
#
# Seeded well under the measured populations so ordinary tree movement never
# trips them, and far enough above zero that a collapsed root cannot pass.
# Measured on this tree: lvgl/ 1150 files (463 .c + 47 .cpp + 640 headers),
# src/ 26 (6 common + 8 fonts + stub + renderer.c + 10 headers), generated/ 18
# (6 .c + 12 headers).
BUILD_INPUT_FLOORS := --floor lvgl/=400 --floor src/=10 --floor generated/=8
BUILD_INPUT_DIGEST := tools/wasm_input_digest.sh

## input-digest: print the digest of what a link from THIS tree would compile.
# The verifier's half of the comparison. Same list, same producer, same floors
# as the link's write below — one home, so the two cannot disagree about what
# "the inputs" are.
.PHONY: input-digest
input-digest:
	@printf '%s\n' $(BUILD_INPUT_FILES) | bash $(BUILD_INPUT_DIGEST) $(BUILD_INPUT_FLOORS)

# Link with clang++ (needed for C++ ThorVG runtime).
# Stub objects (wasm_sjlj_stub) provide setjmp/longjmp for ThorVG's tvgSwRle.
# $(OUT): release -> output/controls.wasm (-O2 -flto, the shipped+gated artifact);
# BUILD=dev -> output/controls.dev.wasm (-O0 -g, no -flto, seconds to relink).
#
# The content stamp is written HERE, after the link, staged and renamed rather
# than redirected in place: a shell creates a redirect target before running the
# command, so a refusing digest would otherwise TRUNCATE the previous — and
# still true — sidecar to nothing. Staged in the destination's own directory so
# the rename cannot cross a filesystem (renderer.mk's install_atomic block
# carries the measurement behind that).
$(OUT): $(LIB_OBJS) $(STUB_OBJS) $(COMMON_APP_OBJS) $(RENDERER_OBJ) $(THORVG_OBJS)
	@mkdir -p output
	$(CXX) $(CXXFLAGS_LINK) $(LDFLAGS) -o $@ $^ $(SJLJ_LIB)
	@printf '%s\n' $(BUILD_INPUT_FILES) | bash $(BUILD_INPUT_DIGEST) $(BUILD_INPUT_FLOORS) \
	  >$@.build-inputs.tmp && mv -f $@.build-inputs.tmp $@.build-inputs

# ── the provenance stamp is its own target, and that is load-bearing ────────
# It used to be written as a side effect of the link above, whose prerequisites
# are OBJECT FILES. PROTOGEN_SHA was not among them, so any commit that moved
# HEAD without touching a renderer source — the harness Cargo.toml, devcards
# tooling, docs, CI wiring — left the stamp naming the OLD commit while make
# correctly reported nothing to do. `make wasm` was then a NO-OP that appears to
# succeed: exit 0, no output, stamp unchanged. Consumers compare this stamp
# against their gitlink precisely BECAUSE byte-identical output does not
# establish provenance, so the artifact whose whole job is answering "built from
# which commit" could quietly answer with a stale one. It failed SAFE (the
# consumer gate reds) but its printed remedy was the very command that no-ops.
#
# The sha is a make VARIABLE and cannot be a prerequisite directly. It is
# written to a file whose mtime moves ONLY when the value actually changes (the
# cmp-before-write idiom renderer.mk uses for the manifests), and THAT is the
# prerequisite. So a HEAD change re-stamps WITHOUT dragging in the multi-second
# serial LTO relink — which is exactly why doing it inside the link recipe was
# tempting and wrong.
.PHONY: sha-probe
output/.protogen-sha: sha-probe
	@mkdir -p output
	@printf '%s\n' '$(PROTOGEN_SHA)' | cmp -s - $@ 2>/dev/null \
	  || printf '%s\n' '$(PROTOGEN_SHA)' >$@

$(OUT).build-sha: $(OUT) output/.protogen-sha
	@cp output/.protogen-sha $@

# reference.wasm: same scaffold + ABI, but the literal-lv_* renderer (diff oracle).
output/reference.wasm: $(LIB_OBJS) $(STUB_OBJS) $(COMMON_APP_OBJS) $(REFERENCE_OBJ) $(DEMO_OBJS) $(THORVG_OBJS)
	@mkdir -p output
	$(CXX) $(CXXFLAGS_LINK) $(LDFLAGS) -o $@ $^

clean:
	rm -f output/controls.wasm output/controls.dev.wasm output/reference.wasm
	rm -f output/controls.wasm.build-sha output/controls.dev.wasm.build-sha
	rm -f output/controls.wasm.build-inputs output/controls.dev.wasm.build-inputs
	rm -f output/controls.wasm.build-inputs.tmp output/controls.dev.wasm.build-inputs.tmp
	rm -f output/.protogen-sha
	rm -rf build

# ── compile database ────────────────────────────────────────────────────────
# The HAND-AUTHORED translation units, for tooling that needs a real compile
# command per file (clang-tidy). Deliberately NOT the vendored LVGL/nanopb/
# ThorVG sets: those are excluded from every gate in this repo, and analysing
# 1100 upstream files to lint 17 of our own is pure noise.
TIDY_SRCS := $(COMMON_APP_SRCS) $(STUB_SRCS) src/renderer.c src/reference_ui.c
TIDY_SRCS := $(filter-out $(FONT_SRCS),$(TIDY_SRCS))

## compile-db: emit compile_commands.json for the hand-authored sources
# Emitted from THIS makefile's own $(CFLAGS)/$(WARN_FLAGS), not reconstructed
# by hand and not intercepted by `bear` (which is not in the image anyway).
# That is the whole point: a hand-assembled flag list silently disagrees with
# the build, and clang-tidy then reports diagnostics the compiler never sees —
# measured here as three phantom "parse errors" on a static_assert that
# compiles cleanly, which vanished the moment the real flags were used.
.PHONY: compile-db
compile-db:
	@printf '[\n' > compile_commands.json
	@first=1; for f in $(TIDY_SRCS); do \
		if [ $$first -eq 0 ]; then printf ',\n' >> compile_commands.json; fi; \
		first=0; \
		printf '  {"directory": "%s", "file": "%s", "command": "%s %s -c %s"}' \
			"$(CURDIR)" "$$f" "$(CC) $(CFLAGS) $(APP_STD)" "$(WARN_FLAGS)" "$$f" \
			>> compile_commands.json; \
	done
	@printf '\n]\n' >> compile_commands.json
	@printf '[compile-db] %s entries -> renderer/compile_commands.json\n' "$(words $(TIDY_SRCS))"

.PHONY: all objects reference clean sha-probe input-digest
