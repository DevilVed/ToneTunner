## 2024-05-22 - Dynamic Status Accessibility
**Learning:** Dynamic text views that convey application state (like "Processing" or "Recording") are often missed by screen reader users if they don't have focus.
**Action:** Use `android:accessibilityLiveRegion="polite"` on status TextViews (`tvStatus`) so updates are announced without interrupting the user. This is critical for the "press-record-wait" flow of this app.
