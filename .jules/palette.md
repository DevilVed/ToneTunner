## 2026-01-19 - Android Accessibility Patterns
**Learning:** Found a common pattern in Android XML layouts where `TextView` labels for `Spinner` controls lack the `android:labelFor` attribute, breaking accessibility for screen reader users who can't visually associate the label. Also observed dynamic status text updates without `accessibilityLiveRegion`.
**Action:** Always check `Spinner` and other form controls for proper `labelFor` association and ensure dynamic status views announce updates using `accessibilityLiveRegion="polite"`.
