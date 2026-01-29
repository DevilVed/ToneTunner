## 2026-01-29 - Improving Touch Targets with Clickable Containers
**Learning:** Users often struggle with small checkboxes. Wrapping the checkbox and its label in a `LinearLayout`, making the *layout* clickable/focusable, and forwarding the click to the checkbox (`performClick()`) drastically improves usability.
**Action:** Always wrap small controls with their labels in a clickable container to maximize the touch target area. Ensure visual feedback (ripple) is added to the container.
