## 2024-12-27 - Zip Slip Vulnerability in SetupActivity
**Vulnerability:** A Zip Slip vulnerability was identified in `SetupActivity.java` where zip entries were extracted without validating that the destination path lies within the target directory.
**Learning:** The application allows users to select a zip file for model installation. If a malicious zip file is selected, it could overwrite arbitrary files on the device accessible to the application. This highlights the importance of validating file paths from untrusted sources, even if the user initiates the action.
**Prevention:** Always validate the canonical path of the destination file to ensure it starts with the canonical path of the target directory before writing any data. Use `File.getCanonicalPath()` for this check.
