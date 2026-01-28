## 2024-10-24 - Zip Slip in SetupActivity
**Vulnerability:** A Zip Slip vulnerability was found in `SetupActivity.java` where `ZipEntry.getName()` was used directly to create files, allowing path traversal (e.g., `../../evil.sh`) to overwrite files outside the target directory.
**Learning:** Developers often assume `ZipEntry.getName()` is safe or relative to the extraction root, but the Zip standard allows full paths and relative paths that can traverse directories.
**Prevention:** Always validate that the canonical path of the extracted file starts with the canonical path of the target directory (appended with `File.separator`) before writing.
