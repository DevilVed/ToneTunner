## 2024-05-22 - Expanding Touch Targets in Legacy Layouts
**Learning:** In Android `LinearLayout`s containing a label and a checkbox, users expect the entire row to be clickable. To implement this accessible pattern without rewriting the layout to `ConstraintLayout`:
1. Make the parent `LinearLayout` `clickable="true"`, `focusable="true"`, and add a ripple background (`?attr/selectableItemBackground`).
2. Make the child `CheckBox` `clickable="false"` and `focusable="false"`.
3. In code, set an `OnClickListener` on the parent that calls `checkBox.performClick()`.
This ensures a large touch target, visual feedback, and correct accessibility behavior (single focusable element) while preserving existing `OnCheckedChangeListener` logic.
**Action:** Always wrap small toggle controls in clickable containers for better touch accessibility.
