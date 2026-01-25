## 2024-10-25 - Legacy Accessibility Patterns
**Learning:** Found usage of `ProgressBar` with `progressBarStyleHorizontal` being used purely as a visual divider line. This creates confusion for screen readers if not marked `importantForAccessibility="no"`.
**Action:** When inspecting legacy XML layouts, check "divider" elements for semantic correctness. If visual-only hacks are used, ensure they are hidden from a11y tools.

## 2024-10-25 - Tiny Checkbox Targets
**Learning:** The app uses `LinearLayout` > `TextView` + `CheckBox` rows but only the CheckBox itself was interactive. This is a common pattern in older Android apps that frustrates users.
**Action:** Always wrap these rows in a clickable container (or add listeners to the container) to expand the touch target to the full row width.
