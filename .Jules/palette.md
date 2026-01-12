## 2024-05-23 - Missing labelFor on Spinners
**Learning:** Found a recurring pattern where `TextView` labels for `Spinner` components were visually associated but lacked programmatic association via `android:labelFor`.
**Action:** When inspecting Android layouts with form inputs, always check if the preceding `TextView` has `labelFor` pointing to the input ID, especially for Spinners which often lack internal labels.
