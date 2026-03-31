## 2024-05-24 - [Zip Slip Vulnerability in ZipInputStream]
**Vulnerability:** Zip Slip vulnerability in `SetupActivity.zipExtract` where `ZipInputStream` extracts files to paths dictated by the zip entry name without checking if it traverses outside the intended target directory.
**Learning:** `ZipInputStream` in Java/Android does not validate paths automatically. If a zip entry name contains relative paths like `../`, it can be extracted outside of the expected directory, leading to arbitrary file overwrite.
**Prevention:** Always validate that the canonical path of the extracted file starts with the canonical path of the target directory using `extractedFile.getCanonicalPath().startsWith(canonicalTargetDirPath + File.separator)`. Throw a `SecurityException` if the check fails.
