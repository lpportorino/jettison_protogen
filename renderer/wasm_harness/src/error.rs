//! Error types for the `lvgl_harness` crate.
/// Errors that can occur in the LVGL WASM test harness.
#[derive(Debug, thiserror::Error)]
#[non_exhaustive]
pub enum HarnessError {
    /// WASM module or wasmtime runtime error.
    #[error("wasm: {0}")]
    Wasm(String),
    /// Framebuffer dimension or data error.
    #[error("framebuffer: {0}")]
    Framebuffer(String),
    /// I/O error (file read/write).
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    /// PNG encoding error.
    #[error("png encode: {0}")]
    PngEncode(String),
    /// Semantic tree-diff / JSON parse error.
    #[error("tree-diff: {0}")]
    TreeDiff(String),
    /// The content-sanity oracle tripped — a (near-)blank render.
    #[error("content: {0}")]
    Content(String),
}
