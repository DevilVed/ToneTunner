## 2024-05-21 - Zip Slip in SetupActivity

**Vulnerability:** `SetupActivity.zipExtract` was vulnerable to Zip Slip. It directly used `zipEntry.getName()` to create destination files without validating that the path remained within the target directory. A malicious zip file with entries like `../pwned.txt` could overwrite files outside the intended extraction folder.

**Learning:** `ZipInputStream` does not validate paths. Any file extraction from an archive (Zip, Tar, etc.) must validate the destination path to prevent traversal attacks.

**Prevention:** Always validate that `extractedFile.getCanonicalPath()` starts with `targetDir.getCanonicalPath() + File.separator` before writing to the file.
