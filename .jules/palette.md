# Palette's Journal

## 2024-05-22 - [Accessiblity Patterns in Legacy XML]
**Learning:** This codebase uses legacy XML layouts where `labelFor` attributes are missing for `TextView`s that serve as labels for input controls (Spinner, TextView). This breaks the accessible name calculation for screen readers.
**Action:** When touching any XML layout, scan for "label-like" TextViews preceding input fields and add `android:labelFor="@id/targetId"`.

## 2024-05-22 - [Inaccessible Touch Listeners]
**Learning:** `MainActivity` uses a custom `OnTouchListener` on `btnRecord` to implement "press and hold" recording. This is inaccessible to keyboard users and switch access users who cannot simulate "hold" gestures easily.
**Action:** Future refactor should introduce a "Click to Toggle" mode or alternative input method service triggers to support non-touch users.
