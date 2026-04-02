## 2024-05-24 - Accessibility: Text scaling with sp units
**Learning:** Found several `android:textSize` values hardcoded with `dp` units instead of `sp` units in `activity_download.xml` and `voice_service.xml`. Using `dp` prevents text from scaling with system accessibility settings, severely impacting visually impaired users.
**Action:** Always use `sp` (scale-independent pixels) for text sizes in Android XML layouts. Double-check layout files using `grep -rnw "app/src/main/res/layout" -e "textSize=\".*dp\""` to ensure no `dp` usage for text remains.
