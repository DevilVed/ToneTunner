# Palette's Journal

## 2024-05-22 - Accessibility Patterns in Legacy XML
**Learning:** Found decorative ImageViews (`@id/icon` in `activity_download.xml`) explicitly marked as focusable, creating "ghost" tab stops for screen reader users.
**Action:** Default to `importantForAccessibility="no"` for all non-interactive icons/images.

## 2024-05-22 - Unit Confusion in Typography
**Learning:** Text sizes defined in `dp` (e.g., `22dp`) prevent text scaling for visually impaired users.
**Action:** Always verify `sp` units for `textSize` in layout XMLs.
