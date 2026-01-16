## 2024-12-27 - Accessibility Patterns in ToneTunner
**Learning:** The app uses standard Views (TextView, Spinner) but often misses semantic associations (labelFor) and live region announcements for status updates. It also uses ProgressBar for visual dividers which confuses screen readers.
**Action:** Always check `labelFor` on TextViews preceding input fields and ensure status updates use `accessibilityLiveRegion`. Mark decorative elements as `importantForAccessibility="no"`.
