## 2024-05-24 - [Zip Slip] Fix path traversal in ZIP extraction
**Vulnerability:** Zip Slip vulnerability in `SetupActivity.java` during ZIP extraction.
**Learning:** `SetupActivity` accepts arbitrary ZIP files (via SAF intents) and previously extracted them by directly utilizing `zipEntry.getName()` into `new File(targetDir, zipEntry.getName())` without canonical path validation. If an entry contained traversal sequences (e.g., `../../malicious`), it could overwrite arbitrary files outside the application's internal files directory.
**Prevention:** Always validate extracted files by checking if their canonical path `startsWith` the target directory's canonical path. Additionally, append `File.separator` to the target canonical path to prevent partial string matching vulnerabilities.
