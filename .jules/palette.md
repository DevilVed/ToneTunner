## 2024-05-24 - Accessibility Improvements in Legacy Layouts
**Learning:** Legacy XML layouts in this repo often use decorative elements (like ProgressBars for spacing) that are focusable/visible to screen readers, and form labels are visual-only.
**Action:** Systematically audit XML layouts for `importantForAccessibility="no"` on decorative views and `labelFor` on TextViews associated with inputs.
