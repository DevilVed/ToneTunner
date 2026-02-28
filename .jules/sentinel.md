## 2024-05-24 - [Zip Slip Vulnerability in ZipInputStream]
**Vulnerability:** Path traversal (Zip Slip) vulnerability in `SetupActivity.zipExtract` allowing arbitrary file overwrite via malicious zip files.
**Learning:** Android's `ZipInputStream` and `ZipEntry` do not automatically validate or sanitize paths. By default, `zipEntry.getName()` can return paths containing `../`, leading to extraction outside the intended directory.
**Prevention:** Always use `File.getCanonicalPath()` to resolve the destination path and the target directory. Ensure the resolved destination path explicitly `startsWith` the target directory path appended with `File.separator` before extracting.
