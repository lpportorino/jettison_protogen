//! Framebuffer CV analysis for visual regression tests.
//!
//! Pure-Rust functions operating on `&[u8]` RGBA data. No external dependencies —
//! uses `HashMap` for histograms and BFS for connected component detection.
//!
//! These functions process raw pixel buffers where indexing and arithmetic are
//! fundamental operations validated by the callers (width * height * 4 = len).
// Pixel-processing code — indexing and arithmetic are inherent to the domain.
// All buffer accesses are bounded by width*height validated at call sites.
#![allow(
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::as_conversions,
    clippy::cast_precision_loss,
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_lossless,
    clippy::suboptimal_flops,
    clippy::imprecise_flops,
    reason = "pixel-processing code with validated buffer dimensions"
)]
use std::collections::{HashMap, VecDeque};
/// Axis-aligned bounding box in pixel coordinates.
#[non_exhaustive]
#[derive(Debug, Clone)]
pub struct BoundingBox {
    /// X coordinate of the top-left corner.
    pub x: u32,
    /// Y coordinate of the top-left corner.
    pub y: u32,
    /// Width of the bounding box.
    pub w: u32,
    /// Height of the bounding box.
    pub h: u32,
}
/// A connected region of matching-color pixels.
#[non_exhaustive]
#[derive(Debug, Clone)]
pub struct DetectedRegion {
    /// Bounding box enclosing the region.
    pub bbox: BoundingBox,
    /// Number of pixels in the region.
    pub pixel_count: usize,
}
/// A detected circular region with quality metric.
#[non_exhaustive]
#[derive(Debug, Clone)]
pub struct DetectedCircle {
    /// X coordinate of the centroid.
    pub cx: f64,
    /// Y coordinate of the centroid.
    pub cy: f64,
    /// Mean radius from centroid to edge pixels.
    pub radius: f64,
    /// Circularity metric: 1.0 = perfect circle. Values > 0.7 are "circular".
    pub circularity: f64,
}
/// A color bucket with its pixel count.
pub type ColorCount = ((u8, u8, u8), usize);
/// Bundled parameters for pixel color matching in BFS operations.
struct PixelMatchCtx<'a> {
    /// RGBA pixel buffer.
    rgba: &'a [u8],
    /// Image width in pixels.
    width: u32,
    /// Image height in pixels.
    height: u32,
    /// Target color to match.
    color: (u8, u8, u8),
    /// Per-channel tolerance for matching.
    tolerance: u8,
}
/// Check if any opaque pixel in the framebuffer matches `color` within `tolerance`.
///
/// Skips pixels with alpha < 128. Uses per-channel absolute distance.
pub fn has_color(rgba: &[u8], color: (u8, u8, u8), tolerance: u8) -> bool {
    rgba.chunks_exact(4)
        .any(|px| px[3] >= 128 && channel_match(px[0], px[1], px[2], color, tolerance))
}
/// Fraction of opaque pixels matching `color` within `tolerance`.
pub fn color_coverage(rgba: &[u8], color: (u8, u8, u8), tolerance: u8) -> f64 {
    let mut total_opaque = 0_usize;
    let mut matching = 0_usize;
    for px in rgba.chunks_exact(4) {
        if px[3] >= 128 {
            total_opaque += 1;
            if channel_match(px[0], px[1], px[2], color, tolerance) {
                matching += 1;
            }
        }
    }
    if total_opaque == 0 {
        return 0.0;
    }
    matching as f64 / total_opaque as f64
}
/// Return the top N dominant colors by pixel count.
///
/// Quantizes to 4-bit per channel (4096 buckets) for grouping, then returns
/// the representative color (average of all pixels in that bucket).
pub fn dominant_colors(rgba: &[u8], top_n: usize) -> Vec<ColorCount> {
    let mut buckets: HashMap<u16, (u64, u64, u64, usize)> = HashMap::new();
    for px in rgba.chunks_exact(4) {
        if px[3] < 128 {
            continue;
        }
        let qr = (px[0] >> 4) as u16;
        let qg = (px[1] >> 4) as u16;
        let qb = (px[2] >> 4) as u16;
        let key = (qr << 8) | (qg << 4) | qb;
        let entry = buckets.entry(key).or_insert((0, 0, 0, 0));
        entry.0 += px[0] as u64;
        entry.1 += px[1] as u64;
        entry.2 += px[2] as u64;
        entry.3 += 1;
    }
    let mut sorted: Vec<_> = buckets.into_iter().collect();
    sorted.sort_by_key(|b| std::cmp::Reverse(b.1.3));
    sorted
        .into_iter()
        .take(top_n)
        .map(|(_, (sr, sg, sb, count))| {
            let avg_r = (sr / count as u64) as u8;
            let avg_g = (sg / count as u64) as u8;
            let avg_b = (sb / count as u64) as u8;
            ((avg_r, avg_g, avg_b), count)
        })
        .collect()
}
/// Find all connected regions of `color`-matching pixels via BFS flood fill.
#[allow(
    clippy::too_many_lines,
    reason = "BFS algorithm is inherently sequential"
)]
pub fn find_color_regions(
    rgba: &[u8],
    width: u32,
    height: u32,
    color: (u8, u8, u8),
    tolerance: u8,
) -> Vec<DetectedRegion> {
    let ctx = PixelMatchCtx {
        rgba,
        width,
        height,
        color,
        tolerance,
    };
    let total = (width * height) as usize;
    let mut visited = vec![false; total];
    let mut regions = Vec::new();
    for row in 0..height {
        for col in 0..width {
            let idx = (row * width + col) as usize;
            if visited[idx] {
                continue;
            }
            let px_off = idx * 4;
            if rgba[px_off + 3] < 128 {
                visited[idx] = true;
                continue;
            }
            if !channel_match(
                rgba[px_off],
                rgba[px_off + 1],
                rgba[px_off + 2],
                color,
                tolerance,
            ) {
                visited[idx] = true;
                continue;
            }
            let region = bfs_flood_fill(&ctx, &mut visited, col, row);
            if region.pixel_count >= 4 {
                regions.push(region);
            }
        }
    }
    regions.sort_by_key(|b| std::cmp::Reverse(b.pixel_count));
    regions
}
/// Detect a circular region of `color`-matching pixels.
///
/// Finds the largest connected component, computes its centroid and mean radius,
/// then calculates circularity = `4pi * area / perimeter^2`.
pub fn detect_circle(
    rgba: &[u8],
    width: u32,
    height: u32,
    color: (u8, u8, u8),
    tolerance: u8,
) -> Option<DetectedCircle> {
    let ctx = PixelMatchCtx {
        rgba,
        width,
        height,
        color,
        tolerance,
    };
    let regions = find_color_regions(rgba, width, height, color, tolerance);
    let region = regions.first()?;
    if region.pixel_count < 16 {
        return None;
    }
    let total = (width * height) as usize;
    let mut visited = vec![false; total];
    let seed = find_seed_pixel(&ctx, &region.bbox)?;
    let pixels = collect_region_pixels(&ctx, &mut visited, seed);
    let area = pixels.len() as f64;
    // Centroid
    let (sum_x, sum_y) = pixels
        .iter()
        .fold((0.0_f64, 0.0_f64), |(ax, ay), &(px, py)| {
            (ax + px as f64, ay + py as f64)
        });
    let centroid_x = sum_x / area;
    let centroid_y = sum_y / area;
    // Mean radius
    let mean_radius: f64 = pixels
        .iter()
        .map(|&(px, py)| {
            ((px as f64 - centroid_x).powi(2) + (py as f64 - centroid_y).powi(2)).sqrt()
        })
        .sum::<f64>()
        / area;
    // Perimeter: count pixels with at least one non-matching neighbor
    let perimeter = count_perimeter_pixels(&ctx, &pixels);
    let circularity = if perimeter > 0.0 {
        4.0 * std::f64::consts::PI * area / (perimeter * perimeter)
    } else {
        0.0
    };
    Some(DetectedCircle {
        cx: centroid_x,
        cy: centroid_y,
        radius: mean_radius,
        circularity,
    })
}
/// Check which of the specified colors are present in the framebuffer.
pub fn has_any_color(rgba: &[u8], colors: &[(u8, u8, u8)], tolerance: u8) -> Vec<bool> {
    let mut found = vec![false; colors.len()];
    for px in rgba.chunks_exact(4) {
        if px[3] < 128 {
            continue;
        }
        for (idx, &color) in colors.iter().enumerate() {
            if !found[idx] && channel_match(px[0], px[1], px[2], color, tolerance) {
                found[idx] = true;
            }
        }
        if found.iter().all(|&flag| flag) {
            break;
        }
    }
    found
}
// ─── Internal helpers ────────────────────────────────────────────

/// Per-channel absolute distance color match.
fn channel_match(red: u8, green: u8, blue: u8, color: (u8, u8, u8), tolerance: u8) -> bool {
    let tol = i16::from(tolerance);
    let dr = (i16::from(red) - i16::from(color.0)).abs();
    let dg = (i16::from(green) - i16::from(color.1)).abs();
    let db = (i16::from(blue) - i16::from(color.2)).abs();
    dr <= tol && dg <= tol && db <= tol
}
/// Return 4-connected neighbors within image bounds.
fn pixel_neighbors(px: u32, py: u32, width: u32, height: u32) -> Vec<(u32, u32)> {
    let mut result = Vec::with_capacity(4);
    if px > 0 {
        result.push((px - 1, py));
    }
    if px + 1 < width {
        result.push((px + 1, py));
    }
    if py > 0 {
        result.push((px, py - 1));
    }
    if py + 1 < height {
        result.push((px, py + 1));
    }
    result
}
/// Find the first matching pixel within a bounding box.
fn find_seed_pixel(ctx: &PixelMatchCtx<'_>, bbox: &BoundingBox) -> Option<(u32, u32)> {
    for sy in bbox.y..bbox.y + bbox.h {
        for sx in bbox.x..bbox.x + bbox.w {
            let si = (sy * ctx.width + sx) as usize;
            let so = si * 4;
            if ctx.rgba[so + 3] >= 128
                && channel_match(
                    ctx.rgba[so],
                    ctx.rgba[so + 1],
                    ctx.rgba[so + 2],
                    ctx.color,
                    ctx.tolerance,
                )
            {
                return Some((sx, sy));
            }
        }
    }
    None
}
/// Check if a pixel at `(nx, ny)` matches the target color.
fn pixel_matches(ctx: &PixelMatchCtx<'_>, nx: u32, ny: u32) -> bool {
    let ni = (ny * ctx.width + nx) as usize;
    let no = ni * 4;
    ctx.rgba[no + 3] >= 128
        && channel_match(
            ctx.rgba[no],
            ctx.rgba[no + 1],
            ctx.rgba[no + 2],
            ctx.color,
            ctx.tolerance,
        )
}
/// BFS flood fill from a seed pixel, returning the detected region.
fn bfs_flood_fill(
    ctx: &PixelMatchCtx<'_>,
    visited: &mut [bool],
    start_x: u32,
    start_y: u32,
) -> DetectedRegion {
    let mut queue = VecDeque::new();
    queue.push_back((start_x, start_y));
    visited[(start_y * ctx.width + start_x) as usize] = true;
    let mut min_x = start_x;
    let mut min_y = start_y;
    let mut max_x = start_x;
    let mut max_y = start_y;
    let mut count = 0_usize;
    while let Some((cx, cy)) = queue.pop_front() {
        count += 1;
        min_x = min_x.min(cx);
        min_y = min_y.min(cy);
        max_x = max_x.max(cx);
        max_y = max_y.max(cy);
        for &(nx, ny) in &pixel_neighbors(cx, cy, ctx.width, ctx.height) {
            let ni = (ny * ctx.width + nx) as usize;
            if visited[ni] {
                continue;
            }
            visited[ni] = true;
            if pixel_matches(ctx, nx, ny) {
                queue.push_back((nx, ny));
            }
        }
    }
    DetectedRegion {
        bbox: BoundingBox {
            x: min_x,
            y: min_y,
            w: max_x - min_x + 1,
            h: max_y - min_y + 1,
        },
        pixel_count: count,
    }
}
/// BFS collect all pixels in a region from a seed point.
fn collect_region_pixels(
    ctx: &PixelMatchCtx<'_>,
    visited: &mut [bool],
    seed: (u32, u32),
) -> Vec<(u32, u32)> {
    let mut pixels = Vec::new();
    let mut queue = VecDeque::new();
    queue.push_back(seed);
    visited[(seed.1 * ctx.width + seed.0) as usize] = true;
    while let Some((cx, cy)) = queue.pop_front() {
        pixels.push((cx, cy));
        for &(nx, ny) in &pixel_neighbors(cx, cy, ctx.width, ctx.height) {
            let ni = (ny * ctx.width + nx) as usize;
            if visited[ni] {
                continue;
            }
            visited[ni] = true;
            if pixel_matches(ctx, nx, ny) {
                queue.push_back((nx, ny));
            }
        }
    }
    pixels
}
/// Count pixels on the perimeter (having at least one non-matching neighbor).
fn count_perimeter_pixels(ctx: &PixelMatchCtx<'_>, pixels: &[(u32, u32)]) -> f64 {
    pixels
        .iter()
        .filter(|&&(px, py)| {
            let all_match = pixel_neighbors(px, py, ctx.width, ctx.height)
                .iter()
                .all(|&(nx, ny)| pixel_matches(ctx, nx, ny));
            !all_match
        })
        .count() as f64
}
#[cfg(test)]
#[allow(
    clippy::expect_used,
    clippy::unwrap_used,
    clippy::missing_docs_in_private_items,
    clippy::missing_panics_doc,
    missing_docs,
    reason = "unit tests"
)]
mod tests {
    use super::*;
    fn make_solid_rgba(width: u32, height: u32, red: u8, green: u8, blue: u8) -> Vec<u8> {
        let mut buf = vec![0u8; (width * height * 4) as usize];
        for px in buf.chunks_exact_mut(4) {
            px[0] = red;
            px[1] = green;
            px[2] = blue;
            px[3] = 255;
        }
        buf
    }
    #[test]
    fn test_has_color_exact_match() {
        let buf = make_solid_rgba(4, 4, 139, 92, 246);
        assert!(has_color(&buf, (139, 92, 246), 0));
    }
    #[test]
    fn test_has_color_no_match() {
        let buf = make_solid_rgba(4, 4, 139, 92, 246);
        assert!(!has_color(&buf, (0, 0, 0), 5));
    }
    #[test]
    fn test_has_color_within_tolerance() {
        let buf = make_solid_rgba(4, 4, 140, 93, 245);
        assert!(has_color(&buf, (139, 92, 246), 2));
    }
    #[test]
    fn test_color_coverage_full() {
        let buf = make_solid_rgba(10, 10, 100, 100, 100);
        let cov = color_coverage(&buf, (100, 100, 100), 0);
        assert!((cov - 1.0).abs() < f64::EPSILON);
    }
    #[test]
    fn test_dominant_colors_single() {
        let buf = make_solid_rgba(10, 10, 50, 100, 200);
        let dom = dominant_colors(&buf, 3);
        assert!(!dom.is_empty());
        let (color, count) = &dom[0];
        assert_eq!(*count, 100);
        assert!((i16::from(color.0) - 50).abs() <= 8);
    }
    #[test]
    fn test_find_regions_single_blob() {
        let mut buf = vec![0u8; 20 * 20 * 4];
        for row in 3..8_u32 {
            for col in 3..8_u32 {
                let off = (row * 20 + col) as usize * 4;
                buf[off] = 255;
                buf[off + 3] = 255;
            }
        }
        let regions = find_color_regions(&buf, 20, 20, (255, 0, 0), 5);
        assert_eq!(regions.len(), 1);
        assert_eq!(regions[0].pixel_count, 25);
        assert_eq!(regions[0].bbox.w, 5);
        assert_eq!(regions[0].bbox.h, 5);
    }
    #[test]
    fn test_detect_circle_on_disc() {
        let img_w = 30_u32;
        let img_h = 30_u32;
        let mut buf = vec![0u8; (img_w * img_h * 4) as usize];
        let (center_x, center_y, radius) = (15.0_f64, 15.0_f64, 10.0_f64);
        for row in 0..img_h {
            for col in 0..img_w {
                let dx = f64::from(col) - center_x;
                let dy = f64::from(row) - center_y;
                if dx * dx + dy * dy <= radius * radius {
                    let off = (row * img_w + col) as usize * 4;
                    buf[off] = 34;
                    buf[off + 1] = 211;
                    buf[off + 2] = 238;
                    buf[off + 3] = 255;
                }
            }
        }
        let circle = detect_circle(&buf, img_w, img_h, (34, 211, 238), 5);
        assert!(circle.is_some());
        let detected = circle.unwrap();
        assert!((detected.cx - 15.0).abs() < 1.5, "cx={}", detected.cx);
        assert!((detected.cy - 15.0).abs() < 1.5, "cy={}", detected.cy);
        assert!(
            detected.circularity > 0.7,
            "circularity={}",
            detected.circularity
        );
    }
}
