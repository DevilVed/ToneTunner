## 2024-05-23 - [Small Touch Targets Fix]
**Learning:** Checkboxes in Android often have small touch targets (48dp x 48dp usually, but visual bounds can be smaller). Wrapping the CheckBox and its Label in a clickable LinearLayout increases the target size to the full row width, improving accessibility for all users.
**Action:** When pairing a Label and a CheckBox, make the parent container clickable (`android:clickable="true"`, `android:focusable="true"`) and set an OnClickListener to toggle the child CheckBox.
