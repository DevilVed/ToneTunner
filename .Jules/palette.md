## 2024-11-20 - [Touch Targets on Checkboxes]
**Learning:** UX Standard: Always wrap small controls (like checkboxes) with their labels in a clickable container using `android:background="?attr/selectableItemBackground"` to improve touch targets and visual feedback.
**Action:** When adding or finding small checkboxes or switches in layout, wrap them in a container, make the container focusable/clickable, and wire the container's onClickListener to the child control's `performClick()` in Java.
