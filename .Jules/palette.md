# Palette's Journal

## 2024-10-26 - Missing Labels and Decorative Hacks
**Learning:** Found a pattern where visual labels for Spinners were not programmatically associated using `labelFor`, and ProgressBars were used as visual dividers, confusing screen readers.
**Action:** Always check `labelFor` on TextViews preceding inputs and ensure decorative elements like "divider" ProgressBars are hidden from accessibility services.
