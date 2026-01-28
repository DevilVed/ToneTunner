package com.whisperonnx;

import java.io.File;
import java.io.IOException;

public class ZipSlipSecurityTest {

    public static void main(String[] args) throws IOException {
        testZipSlip();
    }

    public static void testZipSlip() throws IOException {
        File targetDir = new File("safe_dir");
        targetDir.mkdirs();
        // Clean up safe_dir from previous runs or ensure it exists

        String targetDirPath = targetDir.getCanonicalPath();

        // Test Case 1: Safe path
        String safeEntry = "safe.txt";
        validatePath(targetDir, safeEntry);
        System.out.println("Test 1 Passed: Safe path accepted.");

        // Test Case 2: Unsafe path (Zip Slip)
        String unsafeEntry = "../evil.txt";
        try {
            validatePath(targetDir, unsafeEntry);
            System.err.println("Test 2 FAILED: Unsafe path was accepted!");
            System.exit(1);
        } catch (SecurityException e) {
            System.out.println("Test 2 Passed: Unsafe path rejected with: " + e.getMessage());
        }

        // Test Case 3: Nested safe path
        String nestedSafe = "nested/safe.txt";
        validatePath(targetDir, nestedSafe);
        System.out.println("Test 3 Passed: Nested safe path accepted.");
    }

    // This is the logic we will transplant to SetupActivity.java
    public static void validatePath(File targetDir, String zipEntryName) throws IOException {
        File extractedFile = new File(targetDir, zipEntryName);
        String extractedPath = extractedFile.getCanonicalPath();
        String targetPath = targetDir.getCanonicalPath();

        // Ensure targetPath ends with separator for correct startWith check
        if (!targetPath.endsWith(File.separator)) {
            targetPath += File.separator;
        }

        if (!extractedPath.startsWith(targetPath)) {
            throw new SecurityException("Zip Slip vulnerability detected: " + extractedPath);
        }
    }
}
