## 2026-01-18 - [Decorative Progress Bar Anti-Pattern]
**Learning:** Using `ProgressBar` as a visual divider creates noise for screen readers ("0% progress").
**Action:** Always add `android:importantForAccessibility="no"` to decorative elements that mimic interactive ones.
