//! CLI binary for the LVGL controls WASM test harness.
//!
//! Renders LVGL controls to PNG snapshots for visual verification.
// CLI binary — relax strict production lints
#![allow(
    clippy::expect_used,
    clippy::unwrap_used,
    clippy::print_stdout,
    clippy::print_stderr,
    clippy::missing_docs_in_private_items,
    clippy::missing_errors_doc,
    missing_docs,
    unused_crate_dependencies,
    reason = "CLI binary, not library code"
)]
use clap::Parser;
use lvgl_harness::{ControlsHost, HarnessError, HostConfig, composite_on_checkerboard, save_png};
use notify_debouncer_mini::{DebouncedEventKind, new_debouncer};
use std::path::PathBuf;
use std::sync::mpsc;
use std::time::Instant;
use tracing_subscriber::EnvFilter;
/// LVGL Controls WASM Test Harness — render LVGL widgets to PNG snapshots.
#[derive(Parser, Debug)]
#[command(name = "lvgl-harness")]
struct Cli {
    /// Path to controls.wasm.
    #[arg(long, default_value = "lvgl_controls/controls.wasm")]
    wasm: PathBuf,
    /// WASI root directory (optional, preopened as / read-only).
    #[arg(long)]
    wasi_root: Option<PathBuf>,
    /// Proto IR .pb file to render.
    #[arg(long, default_value = "lvgl_controls/output/main_screen.pb")]
    pb: PathBuf,
    /// Framebuffer width in pixels.
    #[arg(long, default_value_t = 960)]
    width: u32,
    /// Framebuffer height in pixels.
    #[arg(long, default_value_t = 540)]
    height: u32,
    /// Breakpoint tiers to render, comma-separated (0=sm, 1=md, 2=lg, 3=xl).
    #[arg(long, default_value = "0", value_delimiter = ',')]
    breakpoint: Vec<i32>,
    /// Themes to render, comma-separated (0=light, 1=dark).
    #[arg(long, default_value = "0", value_delimiter = ',')]
    theme: Vec<i32>,
    /// DPI value.
    #[arg(long, default_value_t = 160)]
    dpi: i32,
    /// Theme family (0=asgard default, 1=vanilla, 2=stock).
    #[arg(long, default_value_t = 0)]
    theme_family: i32,
    /// PNG output directory.
    #[arg(long, default_value = "lvgl_controls/snapshots")]
    output: PathBuf,
    /// Enable watch mode (hot-reload on .wasm or .pb changes).
    #[arg(long)]
    watch: bool,
    /// Render with checkerboard transparency background.
    #[arg(long)]
    checkerboard: bool,
    /// Exact tick count per render (pinned — both sides of a differential
    /// run MUST use the same value; see `lvgl_harness::RENDER_TICKS`).
    #[arg(long, default_value_t = lvgl_harness::RENDER_TICKS)]
    ticks: u32,
    /// Also dump the semantic widget tree (JSON) alongside each PNG.
    #[arg(long)]
    dump_tree: bool,
    /// Content-sanity oracle: fail when the render is (near-)blank — too few
    /// OPAQUE pixels (the framebuffer is transparent outside drawn widgets),
    /// or an opaque-majority frame that is a single flat color. Two
    /// identically-BLANK screens would otherwise pass the differential's
    /// equality oracles.
    #[arg(long)]
    assert_content: bool,
    /// Minimum opaque-pixel share required under --assert-content.
    #[arg(long, default_value_t = 0.0001)]
    min_content_ratio: f64,
}
fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env())
        .init();
    let cli = Cli::parse();
    if let Err(err) = run(&cli) {
        eprintln!("error: {err}");
        std::process::exit(1);
    }
}
fn run(cli: &Cli) -> Result<(), HarnessError> {
    std::fs::create_dir_all(&cli.output)?;
    render_all(cli)?;
    if cli.watch {
        watch_loop(cli)?;
    }
    Ok(())
}
/// Render all (breakpoint, theme) variant PNGs from a single WASM instance.
fn render_all(cli: &Cli) -> Result<(), HarnessError> {
    let mut config = HostConfig::new(cli.wasm.clone(), cli.width, cli.height);
    if let Some(root) = &cli.wasi_root {
        config = config.with_wasi_root(root.clone());
    }
    let mut host = ControlsHost::new(&config)?;
    let pb_data = std::fs::read(&cli.pb)?;
    let stem = cli
        .pb
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("output");
    for &bp in &cli.breakpoint {
        for &theme in &cli.theme {
            let start = Instant::now();
            let theme_name = if theme == 0 { "light" } else { "dark" };
            host.set_breakpoint(bp)?;
            host.set_theme_dark(theme)?;
            host.set_dpi(cli.dpi)?;
            host.set_theme_family(cli.theme_family)?;
            host.load_ui(&pb_data)?;
            // Exactly `ticks` ticks — a pinned budget, not an adaptive
            // settle loop: a differential render must be a deterministic
            // function of the module + inputs, identical for both sides.
            let mut flushed = false;
            for _ in 0..cli.ticks {
                if host.tick(lvgl_harness::TICK_MS)? {
                    flushed = true;
                }
            }
            if !flushed {
                return Err(HarnessError::Framebuffer(format!(
                    "no flush within the {} pinned ticks — raise --ticks",
                    cli.ticks
                )));
            }
            let mut framebuffer = host.read_framebuffer()?;
            if cli.checkerboard {
                composite_on_checkerboard(&mut framebuffer, cli.width, cli.height, 8)?;
            }
            if cli.assert_content {
                assert_content(&framebuffer, cli.min_content_ratio, stem)?;
            }
            let filename = format!("{stem}_bp{bp}_{theme_name}.png");
            let out_path = cli.output.join(&filename);
            save_png(&framebuffer, cli.width, cli.height, &out_path)?;
            let elapsed_ms = elapsed_to_ms(start.elapsed());
            tracing::info!(
                bp,
                theme = theme_name,
                path = %out_path.display(),
                ms = elapsed_ms,
                "rendered snapshot"
            );
            if cli.dump_tree {
                let tree = host.dump_tree()?;
                let tree_path = cli
                    .output
                    .join(format!("{stem}_bp{bp}_{theme_name}.tree.json"));
                std::fs::write(&tree_path, &tree)?;
                tracing::info!(
                    path = %tree_path.display(),
                    bytes = tree.len(),
                    "dumped widget tree"
                );
            }
        }
    }
    Ok(())
}
/// Convert a duration to milliseconds as u64, saturating on overflow.
fn elapsed_to_ms(duration: std::time::Duration) -> u64 {
    u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
}
/// The content-sanity oracle behind `--assert-content` (see the flag doc).
/// `dominant_colors` counts OPAQUE pixels only, so: blank ⟺ (near-)zero
/// opaque pixels, OR an opaque-majority frame painted one flat color.
fn assert_content(
    framebuffer: &[u8],
    min_content_ratio: f64,
    stem: &str,
) -> Result<(), HarnessError> {
    // 4096 = every possible 4-bit-quantized bucket → the sum is the TRUE
    // opaque-pixel count, not a top-N undercount.
    let top = lvgl_harness::analysis::dominant_colors(framebuffer, 4096);
    let total_pixels = framebuffer.len() / 4;
    let opaque: usize = top.iter().map(|&(_, count)| count).sum::<usize>();
    #[expect(
        clippy::cast_precision_loss,
        reason = "pixel counts are far below f64's exact-integer range"
    )]
    let opaque_ratio = opaque as f64 / total_pixels as f64;
    let flat_opaque = top.len() < 2 && opaque_ratio > 0.5;
    tracing::info!(
        stem,
        opaque_ratio,
        distinct = top.len(),
        dominant = ?top.first(),
        "content stats"
    );
    if opaque_ratio < min_content_ratio || flat_opaque {
        return Err(HarnessError::Content(format!(
            "content sanity failed for {stem}: opaque-pixel share \
             {opaque_ratio:.5} (flat_opaque={flat_opaque}) — (near-)blank \
             render"
        )));
    }
    Ok(())
}
/// Watch for changes to .wasm and .pb files and re-render.
fn watch_loop(cli: &Cli) -> Result<(), HarnessError> {
    let (tx, rx) = mpsc::channel();
    let mut debouncer = new_debouncer(std::time::Duration::from_millis(300), tx)
        .map_err(|err| HarnessError::Io(std::io::Error::other(format!("debouncer init: {err}"))))?;
    // Watch the .wasm file's parent directory
    let wasm_dir = cli
        .wasm
        .parent()
        .unwrap_or_else(|| std::path::Path::new("."));
    debouncer
        .watcher()
        .watch(wasm_dir, notify::RecursiveMode::NonRecursive)
        .map_err(|err| HarnessError::Io(std::io::Error::other(format!("watch wasm dir: {err}"))))?;
    // Watch the .pb file's parent directory
    let pb_dir = cli.pb.parent().unwrap_or_else(|| std::path::Path::new("."));
    if pb_dir != wasm_dir {
        debouncer
            .watcher()
            .watch(pb_dir, notify::RecursiveMode::NonRecursive)
            .map_err(|err| {
                HarnessError::Io(std::io::Error::other(format!("watch pb dir: {err}")))
            })?;
    }
    tracing::info!(
        wasm = %cli.wasm.display(),
        pb = %cli.pb.display(),
        "watching for changes (Ctrl+C to stop)"
    );
    watch_event_loop(&rx, cli);
    Ok(())
}
/// Process file-change events from the debouncer channel.
fn watch_event_loop(
    rx: &mpsc::Receiver<Result<Vec<notify_debouncer_mini::DebouncedEvent>, notify::Error>>,
    cli: &Cli,
) {
    let wasm_canonical = cli.wasm.canonicalize().ok();
    let pb_canonical = cli.pb.canonicalize().ok();
    loop {
        match rx.recv() {
            Ok(Ok(events)) => {
                let (wasm_changed, pb_changed) =
                    classify_events(&events, wasm_canonical.as_ref(), pb_canonical.as_ref());
                if wasm_changed || pb_changed {
                    let what = if wasm_changed { "wasm" } else { "pb" };
                    tracing::info!(changed = what, "re-rendering");
                    match render_all(cli) {
                        Ok(()) => tracing::info!("re-render complete"),
                        Err(err) => tracing::warn!(%err, "re-render failed"),
                    }
                }
            }
            Ok(Err(errs)) => {
                tracing::warn!(?errs, "watch error");
            }
            Err(_recv_err) => {
                // Channel closed — watcher dropped
                break;
            }
        }
    }
}
/// Classify debounced events into wasm/pb change flags.
fn classify_events(
    events: &[notify_debouncer_mini::DebouncedEvent],
    wasm_canonical: Option<&PathBuf>,
    pb_canonical: Option<&PathBuf>,
) -> (bool, bool) {
    let mut wasm_changed = false;
    let mut pb_changed = false;
    for event in events {
        if event.kind != DebouncedEventKind::Any {
            continue;
        }
        let event_canonical = event.path.canonicalize().ok();
        if event_canonical.as_ref() == wasm_canonical {
            wasm_changed = true;
        }
        if event_canonical.as_ref() == pb_canonical {
            pb_changed = true;
        }
    }
    (wasm_changed, pb_changed)
}
