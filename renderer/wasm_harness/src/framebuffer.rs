//! PNG encoding for RGBA framebuffer data.
use crate::HarnessError;
use std::path::Path;
/// Composite RGBA framebuffer onto a checkerboard pattern (in-place).
///
/// Standard transparency indicator: alternating light/dark gray squares.
/// Uses Porter-Duff source-over with integer rounding for correct blending.
/// Areas with alpha=0 become pure checkerboard; alpha=255 stays unchanged.
///
/// # Errors
///
/// Returns an error if `rgba_data` length does not match `width * height * 4`.
pub fn composite_on_checkerboard(
    rgba_data: &mut [u8],
    width: u32,
    height: u32,
    cell_size: u32,
) -> Result<(), HarnessError> {
    let expected_len = width
        .checked_mul(height)
        .and_then(|pixels| pixels.checked_mul(4))
        .ok_or_else(|| HarnessError::Framebuffer("framebuffer size overflow".into()))?;
    #[expect(
        clippy::as_conversions,
        reason = "u32→usize is lossless on all supported platforms (32-bit+)"
    )]
    let expected = expected_len as usize;
    if rgba_data.len() != expected {
        return Err(HarnessError::Framebuffer(format!(
            "data length {} does not match {width}x{height}x4 = {expected}",
            rgba_data.len()
        )));
    }
    let light: u8 = 0xFF; // 255 — white square
    let dark: u8 = 0xCC; // 204 — light gray square
    #[expect(
        clippy::arithmetic_side_effects,
        reason = "pixel arithmetic is bounded by validated width*height*4 buffer size"
    )]
    for py in 0..height {
        for px in 0..width {
            #[expect(
                clippy::as_conversions,
                reason = "u32→usize is lossless on all supported platforms (32-bit+)"
            )]
            let idx = ((py * width + px) * 4) as usize;
            let Some(&sa) = rgba_data.get(idx + 3) else {
                continue;
            };
            // Fully opaque — checkerboard hidden, skip
            if sa == 255 {
                continue;
            }
            let cell_idx = px / cell_size + py / cell_size;
            let bg = if cell_idx.is_multiple_of(2) {
                light
            } else {
                dark
            };
            let pixel: &mut [u8; 4] = rgba_data
                .get_mut(idx..idx + 4)
                .and_then(|s| <&mut [u8; 4]>::try_from(s).ok())
                .ok_or_else(|| HarnessError::Framebuffer("pixel index out of bounds".into()))?;
            if sa == 0 {
                // Fully transparent — write checkerboard directly
                *pixel = [bg, bg, bg, 255];
            } else {
                // Partial alpha — blend source over checkerboard
                let [sr, sg, sb, _] = *pixel;
                let inv_a = 255 - sa;
                // Sree's integer rounding trick for correct Porter-Duff blending
                let blend = |src: u8, dst: u8| -> u8 {
                    let src_term = u16::from(src) * u16::from(sa.wrapping_add(1));
                    let dst_term = u16::from(dst) * u16::from(inv_a.wrapping_add(1));
                    #[expect(clippy::as_conversions, reason = "u16 >> 8 always fits in u8")]
                    {
                        ((src_term + dst_term) >> 8) as u8
                    }
                };
                *pixel = [blend(sr, bg), blend(sg, bg), blend(sb, bg), 255];
            }
        }
    }
    Ok(())
}
/// Encode RGBA8 framebuffer data as a PNG and write it to `path`.
///
/// # Errors
///
/// Returns an error if the data length does not match `width * height * 4`,
/// or if file creation / PNG encoding fails.
pub fn save_png(
    rgba_data: &[u8],
    width: u32,
    height: u32,
    path: &Path,
) -> Result<(), HarnessError> {
    let expected_len = width
        .checked_mul(height)
        .and_then(|pixels| pixels.checked_mul(4))
        .ok_or_else(|| HarnessError::Framebuffer("framebuffer size overflow".into()))?;
    #[expect(
        clippy::as_conversions,
        reason = "u32→usize is lossless on all supported platforms (32-bit+)"
    )]
    let expected = expected_len as usize;
    if rgba_data.len() != expected {
        return Err(HarnessError::Framebuffer(format!(
            "data length {} does not match {width}x{height}x4 = {expected}",
            rgba_data.len()
        )));
    }
    let file = std::fs::File::create(path)?;
    let writer = std::io::BufWriter::new(file);
    let mut encoder = png::Encoder::new(writer, width, height);
    encoder.set_color(png::ColorType::Rgba);
    encoder.set_depth(png::BitDepth::Eight);
    let mut png_writer = encoder
        .write_header()
        .map_err(|err| HarnessError::PngEncode(format!("write header: {err}")))?;
    png_writer
        .write_image_data(rgba_data)
        .map_err(|err| HarnessError::PngEncode(format!("write data: {err}")))?;
    Ok(())
}
#[cfg(test)]
#[allow(clippy::missing_panics_doc, reason = "test functions")]
mod tests {
    use super::*;
    #[test]
    fn test_save_png_valid_roundtrip() {
        // Arrange: 4x4 solid red RGBA buffer
        let width = 4_u32;
        let height = 4_u32;
        let mut rgba = Vec::new();
        for _ in 0..16 {
            rgba.extend_from_slice(&[255, 0, 0, 255]);
        }
        let dir = std::env::temp_dir().join("lvgl_harness_test");
        drop(std::fs::create_dir_all(&dir));
        let path = dir.join("test_valid.png");
        // Act
        let result = save_png(&rgba, width, height, &path);
        // Assert
        assert!(result.is_ok(), "save_png should succeed for valid data");
        let file_data = std::fs::read(&path).expect("should read written PNG");
        // PNG magic bytes
        assert_eq!(
            file_data.get(..4),
            Some(&[0x89, 0x50, 0x4E, 0x47][..]),
            "file should start with PNG magic"
        );
        drop(std::fs::remove_file(&path));
    }
    #[test]
    fn test_checkerboard_transparent_pixel_becomes_checker_color() {
        // Arrange: 2x2 image, all transparent
        let mut rgba = vec![0_u8; 2 * 2 * 4];
        // Act
        let result = composite_on_checkerboard(&mut rgba, 2, 2, 1);
        // Assert
        assert!(result.is_ok());
        // (0,0) → cell_idx=0 even → light (0xFF)
        assert_eq!(rgba.get(0..4), Some(&[0xFF, 0xFF, 0xFF, 0xFF][..]));
        // (1,0) → cell_idx=1 odd → dark (0xCC)
        assert_eq!(rgba.get(4..8), Some(&[0xCC, 0xCC, 0xCC, 0xFF][..]));
        // (0,1) → cell_idx=1 odd → dark (0xCC)
        assert_eq!(rgba.get(8..12), Some(&[0xCC, 0xCC, 0xCC, 0xFF][..]));
        // (1,1) → cell_idx=2 even → light (0xFF)
        assert_eq!(rgba.get(12..16), Some(&[0xFF, 0xFF, 0xFF, 0xFF][..]));
    }
    #[test]
    fn test_checkerboard_opaque_pixel_unchanged() {
        // Arrange: 1x1 solid red pixel
        let mut rgba = vec![255, 0, 0, 255];
        // Act
        let result = composite_on_checkerboard(&mut rgba, 1, 1, 1);
        // Assert
        assert!(result.is_ok());
        assert_eq!(rgba, vec![255, 0, 0, 255]);
    }
    #[test]
    fn test_checkerboard_partial_alpha_blends() {
        // Arrange: 1x1 pixel with 50% alpha red
        let mut rgba = vec![255, 0, 0, 128];
        // Act
        let result = composite_on_checkerboard(&mut rgba, 1, 1, 1);
        // Assert: blended onto light (0xFF) checker, alpha becomes 255
        assert!(result.is_ok());
        // Red channel: blend(255, 0xFF) with sa=128
        // src_term = 255 * 129 = 32895, dst_term = 255 * 128 = 32640
        // (32895 + 32640) >> 8 = 255
        assert_eq!(rgba.get(3), Some(&255_u8), "alpha should be 255");
        // Exact values depend on rounding, but red should dominate
        assert!(*rgba.first().unwrap() > 128, "red channel should be > 128");
    }
    #[test]
    fn test_checkerboard_wrong_size_returns_error() {
        let mut rgba = vec![0_u8; 10];
        let result = composite_on_checkerboard(&mut rgba, 4, 4, 8);
        assert!(result.is_err());
    }
    #[test]
    fn test_save_png_wrong_size_returns_error() {
        // Arrange: data length doesn't match dimensions
        let rgba = vec![0_u8; 10];
        let path = std::env::temp_dir().join("should_not_exist.png");
        // Act
        let result = save_png(&rgba, 4, 4, &path);
        // Assert
        assert!(result.is_err(), "mismatched data length should fail");
        let err_msg = result.unwrap_err().to_string();
        assert!(
            err_msg.contains("does not match"),
            "error should mention mismatch: {err_msg}"
        );
    }
}
