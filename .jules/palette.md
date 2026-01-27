# Palette's Journal

This journal records critical UX and accessibility learnings.

## 2024-05-22 - Initial Setup
**Learning:** Establishing a dedicated space for UX insights helps track patterns across the codebase.
**Action:** Use this file to record specific accessibility wins and UX patterns found in this project.

## 2024-05-22 - Expanding Touch Targets
**Learning:** Standard Android CheckBoxes have small touch targets (often just the box itself or tightly wrapped text). Users often expect the entire row to be clickable.
**Action:** Wrap CheckBox and its label in a clickable/focusable ViewGroup (like LinearLayout) and use `performClick()` to delegate the row click to the CheckBox. This creates a larger, more accessible hit area without custom views.
