//! Pixel-diff CLI for the visual differential — compares two same-size RGBA
//! PNGs, reports the exact differing-pixel count plus clustered regions
//! (16px-tile connected components, each with bounding box, pixel count and
//! worst per-channel delta), optionally writes a red-overlay diff PNG, and
//! exits non-zero when the count exceeds `--max-diff` (default 0 — the
//! demo-parity bit-equality oracle).
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
use lvgl_harness::HarnessError;
use std::path::{Path, PathBuf};
/// Tile edge for region clustering (pixels).
const TILE: usize = 16;
/// Compare two RGBA PNGs pixel-exactly and localize divergences.
#[derive(Parser, Debug)]
#[command(name = "imgdiff")]
struct Cli {
    /// Reference PNG.
    reference: PathBuf,
    /// PNG under test.
    under_test: PathBuf,
    /// Write a diff overlay PNG (differing pixels red, rest dimmed reference).
    #[arg(long)]
    diff_out: Option<PathBuf>,
    /// Maximum allowed differing-pixel count (exit 1 above it).
    #[arg(long, default_value_t = 0)]
    max_diff: u64,
    /// Maximum regions to print.
    #[arg(long, default_value_t = 16)]
    max_regions: usize,
}
struct Image {
    width: usize,
    height: usize,
    rgba: Vec<u8>,
}
fn load_png(path: &Path) -> Result<Image, HarnessError> {
    let file = std::fs::File::open(path)?;
    let decoder = png::Decoder::new(std::io::BufReader::new(file));
    let mut reader = decoder
        .read_info()
        .map_err(|err| HarnessError::PngEncode(format!("read {}: {err}", path.display())))?;
    let mut buf = vec![0_u8; reader.output_buffer_size()];
    let info = reader
        .next_frame(&mut buf)
        .map_err(|err| HarnessError::PngEncode(format!("decode {}: {err}", path.display())))?;
    if info.color_type != png::ColorType::Rgba || info.bit_depth != png::BitDepth::Eight {
        return Err(HarnessError::PngEncode(format!(
            "{}: expected RGBA8, got {:?}/{:?}",
            path.display(),
            info.color_type,
            info.bit_depth
        )));
    }
    buf.truncate(info.buffer_size());
    #[expect(
        clippy::as_conversions,
        reason = "u32→usize is lossless on all supported platforms (32-bit+)"
    )]
    Ok(Image {
        width: info.width as usize,
        height: info.height as usize,
        rgba: buf,
    })
}
/// One clustered divergence region (tile-grid connected component).
struct Region {
    min_x: usize,
    min_y: usize,
    max_x: usize,
    max_y: usize,
    pixels: u64,
    worst_delta: u8,
}
fn main() {
    let cli = Cli::parse();
    if let Err(err) = run(&cli) {
        eprintln!("error: {err}");
        std::process::exit(2);
    }
}
fn run(cli: &Cli) -> Result<(), HarnessError> {
    let reference = load_png(&cli.reference)?;
    let under_test = load_png(&cli.under_test)?;
    if reference.width != under_test.width || reference.height != under_test.height {
        return Err(HarnessError::Framebuffer(format!(
            "size mismatch: {}x{} vs {}x{}",
            reference.width, reference.height, under_test.width, under_test.height
        )));
    }
    let (total, tiles, overlay) = diff_pixels(&reference, &under_test);
    let mut regions = cluster_tiles(&tiles, reference.width, reference.height);
    fill_worst_deltas(&reference, &under_test, &mut regions);
    let pixel_count = reference.width.checked_mul(reference.height).unwrap();
    #[expect(
        clippy::cast_precision_loss,
        reason = "pixel counts are far below f64's exact-integer range"
    )]
    let pct = 100.0 * total as f64 / pixel_count as f64;
    println!("diff: {total} / {pixel_count} pixels ({pct:.4}%)");
    for region in regions.iter().take(cli.max_regions) {
        println!(
            "  region ({},{})..({},{})  {}px  worst-delta {}",
            region.min_x,
            region.min_y,
            region.max_x,
            region.max_y,
            region.pixels,
            region.worst_delta
        );
    }
    if regions.len() > cli.max_regions {
        println!("  … {} more regions", regions.len() - cli.max_regions);
    }
    if let Some(out) = &cli.diff_out {
        #[expect(
            clippy::as_conversions,
            reason = "image dims originate from u32 PNG headers"
        )]
        lvgl_harness::save_png(
            &overlay,
            reference.width as u32,
            reference.height as u32,
            out,
        )?;
        println!("diff overlay: {}", out.display());
    }
    if total > cli.max_diff {
        std::process::exit(1);
    }
    Ok(())
}
/// Per-pixel compare: returns (count, per-tile diff counts, overlay RGBA).
fn diff_pixels(reference: &Image, under_test: &Image) -> (u64, Vec<u64>, Vec<u8>) {
    let tiles_w = reference.width.div_ceil(TILE);
    let tiles_h = reference.height.div_ceil(TILE);
    let mut tiles = vec![0_u64; tiles_w.checked_mul(tiles_h).unwrap()];
    let mut overlay = vec![0_u8; reference.rgba.len()];
    let mut total = 0_u64;
    for y in 0..reference.height {
        for x in 0..reference.width {
            let i = (y.checked_mul(reference.width).unwrap())
                .checked_add(x)
                .and_then(|p| p.checked_mul(4))
                .unwrap();
            let a = reference.rgba.get(i..i.checked_add(4).unwrap()).unwrap();
            let b = under_test.rgba.get(i..i.checked_add(4).unwrap()).unwrap();
            if a == b {
                // Dimmed reference (keeps context around the red hits).
                let dst = overlay.get_mut(i..i.checked_add(4).unwrap()).unwrap();
                dst[0] = a[0] / 4;
                dst[1] = a[1] / 4;
                dst[2] = a[2] / 4;
                dst[3] = 255;
            } else {
                total = total.checked_add(1).unwrap();
                let tile = (y / TILE).checked_mul(tiles_w).unwrap() + x / TILE;
                *tiles.get_mut(tile).unwrap() = tiles.get(tile).unwrap().checked_add(1).unwrap();
                let dst = overlay.get_mut(i..i.checked_add(4).unwrap()).unwrap();
                dst[0] = 255;
                dst[1] = 0;
                dst[2] = 0;
                dst[3] = 255;
            }
        }
    }
    (total, tiles, overlay)
}
/// Worst per-channel delta inside a tile-region's bounding box.
fn worst_delta_in(reference: &Image, under_test: &Image, region: &Region) -> u8 {
    let mut worst = 0_u8;
    for y in region.min_y..=region.max_y {
        for x in region.min_x..=region.max_x {
            let i = (y.checked_mul(reference.width).unwrap())
                .checked_add(x)
                .and_then(|p| p.checked_mul(4))
                .unwrap();
            for c in 0..4 {
                let a = *reference.rgba.get(i + c).unwrap();
                let b = *under_test.rgba.get(i + c).unwrap();
                worst = worst.max(a.abs_diff(b));
            }
        }
    }
    worst
}
/// Connected components over the non-zero tile grid (4-connectivity),
/// largest-first. `worst_delta` is left 0 here — `fill_worst_deltas`
/// computes it per region from the source images afterwards.
fn cluster_tiles(tiles: &[u64], width: usize, height: usize) -> Vec<Region> {
    let tiles_w = width.div_ceil(TILE);
    let tiles_h = height.div_ceil(TILE);
    let mut visited = vec![false; tiles.len()];
    let mut regions = Vec::new();
    for start in 0..tiles.len() {
        if visited.get(start).copied().unwrap_or(true) || *tiles.get(start).unwrap() == 0 {
            continue;
        }
        let mut stack = vec![start];
        *visited.get_mut(start).unwrap() = true;
        let mut region = Region {
            min_x: usize::MAX,
            min_y: usize::MAX,
            max_x: 0,
            max_y: 0,
            pixels: 0,
            worst_delta: 0,
        };
        while let Some(t) = stack.pop() {
            let tx = t % tiles_w;
            let ty = t / tiles_w;
            region.pixels = region.pixels.checked_add(*tiles.get(t).unwrap()).unwrap();
            region.min_x = region.min_x.min(tx.checked_mul(TILE).unwrap());
            region.min_y = region.min_y.min(ty.checked_mul(TILE).unwrap());
            region.max_x = region
                .max_x
                .max(((tx + 1).checked_mul(TILE).unwrap() - 1).min(width - 1));
            region.max_y = region
                .max_y
                .max(((ty + 1).checked_mul(TILE).unwrap() - 1).min(height - 1));
            let neighbors = [
                (tx > 0).then(|| t - 1),
                (tx + 1 < tiles_w).then(|| t + 1),
                (ty > 0).then(|| t - tiles_w),
                (ty + 1 < tiles_h).then(|| t + tiles_w),
            ];
            for n in neighbors.into_iter().flatten() {
                if !visited.get(n).copied().unwrap_or(true) && *tiles.get(n).unwrap() > 0 {
                    *visited.get_mut(n).unwrap() = true;
                    stack.push(n);
                }
            }
        }
        regions.push(region);
    }
    regions.sort_by_key(|region| std::cmp::Reverse(region.pixels));
    regions
}
/// Fill each region's worst per-channel delta from the source images.
fn fill_worst_deltas(reference: &Image, under_test: &Image, regions: &mut [Region]) {
    for region in regions {
        region.worst_delta = worst_delta_in(reference, under_test, region);
    }
}
