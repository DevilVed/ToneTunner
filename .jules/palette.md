## 2024-05-24 - [Accessibility Live Region for Status Updates]
**Learning:** For dynamic text views that update asynchronously (like "Recording...", "Processing...", "Error" statuses), screen readers will not announce the changes by default unless the view gains focus. Using `android:accessibilityLiveRegion="polite"` ensures these important status updates are announced to visually impaired users without interrupting their current flow.
**Action:** Always add `android:accessibilityLiveRegion="polite"` to `TextView`s that display important asynchronous status or progress updates.
