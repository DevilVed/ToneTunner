## 2025-01-31 - Checkbox Touch Targets
**Learning:** In legacy Android layouts using `LinearLayout`, checkboxes often have tiny touch targets. Users struggle to tap the checkbox itself.
**Action:** Always wrap the checkbox and its label in a clickable container (like `LinearLayout`) with `android:background="?attr/selectableItemBackground"` and programmatically toggle the checkbox on container click. This expands the touch target significantly.
