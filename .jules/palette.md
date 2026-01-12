## 2025-10-27 - Missing Spinner Labels
**Learning:** Spinners often lack `contentDescription` or `labelFor` association even when a visual text label is present.
**Action:** Always check `Spinner` elements for `android:labelFor` on their corresponding `TextView` labels to ensure screen readers announce the label when the spinner is focused.
