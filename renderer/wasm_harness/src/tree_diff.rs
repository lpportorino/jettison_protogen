//! Semantic widget-tree diff — the PRIMARY oracle of the visual differential.
//!
//! Both WASM modules — the proto path (`controls.wasm`) and the independent
//! reference path (`reference.wasm`) — emit a JSON widget tree via
//! `controls_dump_tree`. This module computes the RFC-6902 JSON-Patch transforming
//! the reference tree into the under-test tree: an empty patch means our proto
//! representation is semantically exact; a non-empty patch is a path-addressed,
//! LLM-readable explanation of exactly where the two diverge.
use crate::HarnessError;
use json_patch::diff;
use serde_json::Value;
/// Compute the RFC-6902 JSON-Patch operations transforming `reference` into
/// `under_test`. An empty result means the two trees are semantically identical.
///
/// Each op is rendered as a compact JSON string (e.g.
/// `{"op":"replace","path":"/children/0/coords/3","value":10}`) for direct
/// human / LLM reading.
///
/// # Errors
///
/// Returns [`HarnessError::TreeDiff`] if either input is not valid JSON, or if a
/// resulting patch operation cannot be serialized.
pub fn tree_diff(reference: &str, under_test: &str) -> Result<Vec<String>, HarnessError> {
    let ref_val: Value = serde_json::from_str(reference)
        .map_err(|err| HarnessError::TreeDiff(format!("parse reference tree: {err}")))?;
    let ut_val: Value = serde_json::from_str(under_test)
        .map_err(|err| HarnessError::TreeDiff(format!("parse under-test tree: {err}")))?;
    diff(&ref_val, &ut_val)
        .0
        .iter()
        .map(|op| {
            serde_json::to_string(op)
                .map_err(|err| HarnessError::TreeDiff(format!("serialize patch op: {err}")))
        })
        .collect()
}
#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::missing_panics_doc,
    reason = "test functions"
)]
mod tests {
    use super::*;
    #[test]
    fn identical_trees_have_no_diff() {
        let tree = r#"{"type":"lv_obj","coords":[0,0,9,9],"children":[]}"#;
        let ops = tree_diff(tree, tree).unwrap();
        assert!(ops.is_empty(), "identical trees → no patch, got: {ops:?}");
    }
    #[test]
    fn coord_change_is_reported() {
        let a = r#"{"type":"lv_obj","coords":[0,0,9,9],"children":[]}"#;
        let b = r#"{"type":"lv_obj","coords":[0,0,9,10],"children":[]}"#;
        let ops = tree_diff(a, b).unwrap();
        assert_eq!(ops.len(), 1, "one coord change → one op, got: {ops:?}");
        let op = ops.first().unwrap();
        assert!(
            op.contains("/coords/3"),
            "op should target coord index 3: {op}"
        );
    }
    #[test]
    fn type_change_is_reported() {
        let a = r#"{"type":"lv_obj","coords":[0,0,9,9],"children":[]}"#;
        let b = r#"{"type":"lv_label","coords":[0,0,9,9],"children":[]}"#;
        let ops = tree_diff(a, b).unwrap();
        assert_eq!(ops.len(), 1, "one type change → one op, got: {ops:?}");
    }
    #[test]
    fn invalid_json_errors() {
        assert!(tree_diff("not json", "{}").is_err());
        assert!(tree_diff("{}", "also not json").is_err());
    }
}
