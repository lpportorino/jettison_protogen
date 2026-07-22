# LVGL Controls WASM Module — wasi-sdk Build
#
# Produces: output/controls.wasm (generic LVGL renderer with nanopb protobuf)
# UI definitions are pushed as protobuf AST at runtime.
WASI_SDK ?= /opt/wasi-sdk
CC := $(WASI_SDK)/bin/clang
CXX := $(WASI_SDK)/bin/clang++
SYSROOT := $(WASI_SDK)/share/wasi-sysroot
TARGET := wasm32-wasip1

# Build mode toggle — RELEASE is the default and the ONLY shipped artifact.
#   BUILD=release (default): -O2 -flto -> output/controls.wasm. The four
#     tolerance-0 gates (harness-test/morph-parity/matrix-gate/demo-parity) are
#     gate-cached on wasm.mk and validate THIS exact artifact; its flags must
#     stay byte-identical, so the release branch is unchanged.
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
# Both modes keep the SjLj transform (ThorVG's tvgSwRle.cpp #errors without
# `-mllvm -wasm-enable-sjlj`). But with -flto OFF the dev link has no whole-program
# codegen to lower the __wasm_setjmp family inline, so dev links wasi-sdk's
# libsetjmp.a (-lsetjmp) to supply it. Release needs no -lsetjmp — the -flto link
# lowers SjLj inline.
SJLJ_LIB := -lsetjmp
else
OPT := -O2 -flto
OUT := output/controls.wasm
SJLJ_LIB :=
endif

# Strict warning set — applied to APPLICATION sources only (src/*.c), never to
# vendored LVGL/nanopb. -Wconversion/-Wsign-conversion are deliberately NOT here:
# the renderer is built on intentional proto-int -> LVGL-enum direct casts, so
# they would be pure noise; value-correctness is guarded by the parity gate + the
# planned tolerance-0 visual differential instead.
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
          -I. -Ilvgl -Isrc -Igenerated
CXXFLAGS_COMPILE := --target=$(TARGET) --sysroot=$(SYSROOT) \
            $(OPT) \
            $(DEPFLAGS) \
            -DLV_CONF_INCLUDE_SIMPLE \
            -fno-exceptions -fno-rtti \
            -mllvm -wasm-enable-sjlj \
            -I. -Ilvgl -Isrc -Igenerated \
            -std=c++17
CXXFLAGS_LINK := --target=$(TARGET) --sysroot=$(SYSROOT) \
            $(OPT) \
            -DLV_CONF_INCLUDE_SIMPLE \
            -fno-exceptions -fno-rtti \
            -I. -Ilvgl -Isrc -Igenerated \
            -std=c++17
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
           -Wl,--export=gesture_test_reset \
           -Wl,--export=gesture_test_feed \
           -Wl,--export=gesture_decisions_ptr \
           -Wl,--initial-memory=8388608 \
           -Wl,--max-memory=268435456 \
           -mexec-model=reactor
# Sorted: link order IS wasm byte layout — an unsorted find links in
# filesystem readdir order, making the artifact differ per checkout while
# staying functionally identical. Sorting pins one canonical byte stream.
LVGL_SRCS := $(sort $(shell find lvgl/src -name '*.c' 2>/dev/null))
NANOPB_SRCS := generated/pb_common.c generated/pb_decode.c generated/pb_encode.c

# ui_input.pb.c: the host->WASM control channel (ui.HostToWasm) that
# controls_host_message pb_decodes (R4 pointer entry + lifecycle).
GEN_SRCS := generated/ui_ast.pb.c generated/ui_input.pb.c
CMD_SRCS := $(wildcard generated/jon_shared_cmd*.pb.c) \
               generated/jon_shared_data_types.pb.c
FONT_SRCS := $(wildcard src/font_*.c)
STUB_SRCS := src/wasm_sjlj_stub.c

# Common application sources, shared by both WASM modules. The renderer differs:
#   controls.wasm  <- src/renderer.c     (decodes the proto UI AST — the deployed path)
#   reference.wasm <- src/reference_ui.c (literal lv_* calls — the diff oracle)
COMMON_APP_SRCS := src/main.c src/theme.c src/gesture.c src/svg_decoder.c src/cmd_patch.c $(FONT_SRCS)

# ThorVG C++ sources (internal LVGL build)
THORVG_SRCS := $(sort $(shell find lvgl/src/libs/thorvg -name '*.cpp' 2>/dev/null))

# Object directory for split compilation — keyed by BUILD mode so the release
# (-O2 -flto, LLVM-IR .o) and dev (-O0, native-wasm .o) object caches NEVER mix.
# Toggling `make wasm` <-> `make wasm-dev` is then sound WITHOUT a clean, and each
# mode stays independently incremental. (`clean` removes the whole build/ tree.)
OBJ_DIR := build/$(BUILD)
LIB_OBJS := $(patsubst %.c,$(OBJ_DIR)/%.o,$(LVGL_SRCS) $(NANOPB_SRCS) $(GEN_SRCS) $(CMD_SRCS))
STUB_OBJS := $(patsubst %.c,$(OBJ_DIR)/%.o,$(STUB_SRCS))
COMMON_APP_OBJS := $(patsubst %.c,$(OBJ_DIR)/%.o,$(COMMON_APP_SRCS))
RENDERER_OBJ := $(OBJ_DIR)/src/renderer.o
REFERENCE_OBJ := $(OBJ_DIR)/src/reference_ui.o

# lv_demo_widgets (vendored at lvgl/demos, v9.5.0 tag) — linked into
# reference.wasm ONLY: the demo-parity oracle, never deployed.
DEMO_SRCS := $(wildcard lvgl/demos/widgets/*.c)                    $(wildcard lvgl/demos/widgets/assets/*.c) lvgl/demos/lv_demos.c
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
# (BUILD=dev). TODO(perf): split the ~510 vendored TUs (LVGL+ThorVG+nanopb) into
# a prebuilt static archive so even a release link skips re-archiving them.
all: $(OUT)

# Compile every TU, but do NOT link. For consumers that need the COMPILATION —
# the flags, the diagnostics, the compile_commands entries — and never read the
# linked module. tools/gates/atlas_cpp_dead.sh is the one: it runs `bear` over
# this to capture the real compile commands, then re-runs clang per TU for an AST
# dump. It never opens output/controls.wasm.
#
# The saving is large and it is a property of -flto: with LTO on, compiling only
# emits LLVM IR and ALL backend codegen is deferred to the link. Measured cold on
# a quiet box — objects 3.3s, link 13.2s, full `all` 15.2s. So a consumer that
# only needs compilation pays 3.3s instead of 15.2s.
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

# Every object depends on lv_conf.h: a config toggle (decoder on/off, a
# feature flag) changes what the #if guards compile — without this dep a
# conf edit leaves stale objects and the change silently never ships.
# Library sources: compile without -Werror (LVGL has format-nonliteral warnings)
$(OBJ_DIR)/%.o: %.c lv_conf.h
	@mkdir -p $(dir $@)
	$(CC) $(CFLAGS) -c -o $@ $<

# Application sources: compile as C23 with strict warnings. -std=c23 is applied
# HERE (app rule) only — vendored LVGL/nanopb compile with the generic rule below
# at the toolchain default, so a newer standard never destabilizes upstream code.
$(OBJ_DIR)/src/%.o: src/%.c lv_conf.h
	@mkdir -p $(dir $@)
	$(CC) $(CFLAGS) -std=c23 $(WARN_FLAGS) -c -o $@ $<

# ThorVG C++ sources
$(OBJ_DIR)/%.o: %.cpp lv_conf.h
	@mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS_COMPILE) -c -o $@ $<

# Link with clang++ (needed for C++ ThorVG runtime).
# Stub objects (wasm_sjlj_stub) provide setjmp/longjmp for ThorVG's tvgSwRle.
# $(OUT): release -> output/controls.wasm (-O2 -flto, the shipped+gated artifact);
# BUILD=dev -> output/controls.dev.wasm (-O0 -g, no -flto, seconds to relink).
$(OUT): $(LIB_OBJS) $(STUB_OBJS) $(COMMON_APP_OBJS) $(RENDERER_OBJ) $(THORVG_OBJS)
	@mkdir -p output
	$(CXX) $(CXXFLAGS_LINK) $(LDFLAGS) -o $@ $^ $(SJLJ_LIB)

# reference.wasm: same scaffold + ABI, but the literal-lv_* renderer (diff oracle).
output/reference.wasm: $(LIB_OBJS) $(STUB_OBJS) $(COMMON_APP_OBJS) $(REFERENCE_OBJ) $(DEMO_OBJS) $(THORVG_OBJS)
	@mkdir -p output
	$(CXX) $(CXXFLAGS_LINK) $(LDFLAGS) -o $@ $^

clean:
	rm -f output/controls.wasm output/controls.dev.wasm output/reference.wasm
	rm -rf build

.PHONY: all objects reference clean
