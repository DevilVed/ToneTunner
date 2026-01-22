# Palette's Journal

This journal records critical UX and accessibility learnings for the ToneTunner project.

## 2024-05-22 - [Accessibility] Live Regions and Decorative Elements
**Learning:** In `activity_main.xml`, dynamic status updates (`tvStatus`) were silent to screen readers, and a `ProgressBar` used as a visual separator created confusing noise in the accessibility tree.
**Action:** Always use `android:accessibilityLiveRegion="polite"` for dynamic text updates and `android:importantForAccessibility="no"` for pure UI decoration elements.
