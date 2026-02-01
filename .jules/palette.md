## 2024-05-22 - [Checkboxes Touch Target & A11y]
**Learning:** Legacy XML layouts often use small, isolated CheckBoxes. Wrapping them with their labels in a clickable container (`LinearLayout` with `setOnClickListener` and `background="?attr/selectableItemBackground"`) drastically improves touch targets and accessibility.
**Action:** Apply this pattern to all list-item style boolean toggles.

## 2024-05-22 - [ProgressBar as Divider]
**Learning:** `ProgressBar` is sometimes used as a visual divider in this codebase. This is semantically incorrect and confuses screen readers.
**Action:** Always add `android:importantForAccessibility="no"` when `ProgressBar` is used for decoration only.
