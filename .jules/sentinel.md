## 2026-02-01 - Zip Slip in SetupActivity
**Vulnerability:** `SetupActivity` extracted zip files without validating that the destination path is within the target directory, allowing arbitrary file overwrite via directory traversal characters in zip entries.
**Learning:** `SetupActivity` was exported and used a standard `ZipInputStream` loop without path validation. Previous assumptions or documentation that it was secured were incorrect.
**Prevention:** Always validate the canonical path of the destination file against the canonical path of the target directory before writing to disk when extracting files.
