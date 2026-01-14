## 2024-12-27 - Accessibility Patterns in Legacy Layouts
**Learning:** Found two common accessibility patterns: 1) `TextView` labels preceding inputs (`Spinner`) without `android:labelFor` association, causing screen readers to miss the context. 2) `ProgressBar` used as a decorative visual divider, which creates noisy "0%" announcements for screen reader users.
**Action:** Always verify `labelFor` association for external labels and use `importantForAccessibility="no"` for decorative elements misusing semantic widgets.
