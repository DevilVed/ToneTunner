## 2024-06-03 - Improve Touch Targets for Settings Checkboxes
**Learning:** In Android settings menus, small controls like CheckBoxes often have inadequate touch targets, making them difficult to activate, especially for users with motor impairments.
**Action:** Always wrap small controls with their labels in a clickable, focusable parent container (like `LinearLayout`) using `android:background="?attr/selectableItemBackground"` to provide visual feedback and significantly increase the interactive area. Wire the container's click listener to call `performClick()` on the child control.
