# Palette's Journal

This file tracks critical UX and accessibility learnings.

## 2024-05-23 - Accessibility Patterns
**Learning:** Found multiple instances of missing `android:labelFor` on TextViews associated with Spinners and other controls. This prevents screen readers from announcing the label when the control is focused.
**Action:** Always verify `labelFor` association for form inputs in XML layouts.

## 2024-05-23 - Interaction Barriers
**Learning:** The "Press and Hold" recording button (`btnRecord`) uses a custom `OnTouchListener` that relies on `ACTION_DOWN` and `ACTION_UP`. This is a significant barrier for keyboard users and switch access users who cannot simulate "holding" a touch target.
**Prevention:** Avoid "hold to action" patterns for primary functionality. Use "start/stop" toggles or standard click listeners that work with accessibility services.
